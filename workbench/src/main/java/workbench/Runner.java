package workbench;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import workbench.ProcessDefinition.StepDef;

/**
 * Runs a process DAG with independent steps in parallel. A step is ready when all its dependencies
 * have succeeded and it has not run yet in this run; every ready step starts at once, each on its
 * own virtual thread, while the calling thread coordinates and waits.
 */
public class Runner {

    private final Db db;
    private final ProcessDefinition process;

    public Runner(Db db) {
        this(db, ProcessDefinition.ORDER);
    }

    Runner(Db db, ProcessDefinition process) {
        this.db = db;
        this.process = process;
    }

    /**
     * Creates an execution and runs it synchronously. Returns the execution id.
     *
     * @param simulation failure and delay settings, or null for the happy path without delays
     */
    public long start(Simulation simulation) throws SQLException {
        long executionId = db.insertRunningExecution(simulation);
        run(executionId, simulation);
        return executionId;
    }

    /**
     * Retries a FAILED execution synchronously: every step without a successful attempt runs again
     * once its dependencies have succeeded; succeeded steps are not rerun. Returns false, changing
     * nothing, if the execution is unknown or not FAILED, including when a concurrent retry claimed it first.
     */
    public boolean retry(long executionId) throws SQLException {
        if (!db.claimForRetry(executionId)) {
            return false;
        }
        run(executionId, db.findExecution(executionId).orElseThrow().simulation());
        return true;
    }

    private record StepOutcome(StepDef step, boolean succeeded) {
    }

    /**
     * Runs ready steps in parallel until none is left. Failure policy: once a step fails, no further
     * step starts, even an independent one; steps already running finish and are recorded; then the
     * execution fails. A database error in any step is rethrown after the running steps finished,
     * leaving the execution RUNNING (see README, Known limitations).
     */
    private void run(long executionId, Simulation simulation) throws SQLException {
        Set<String> succeeded = new HashSet<>();
        for (int index : db.succeededSteps(executionId)) {
            succeeded.add(process.step(index).name());
        }
        Set<String> started = new HashSet<>(); // started in this run, whatever their outcome
        boolean failed = false;
        Throwable workerError = null;

        // close() at the end of the block waits for every submitted step: none outlives the run.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletionService<StepOutcome> completions = new ExecutorCompletionService<>(executor);
            int running = 0;
            while (true) {
                if (!failed) {
                    for (StepDef step : readySteps(succeeded, started)) {
                        started.add(step.name());
                        completions.submit(() -> new StepOutcome(step, runAttempt(executionId, step, simulation)));
                        running++;
                    }
                }
                if (running == 0) {
                    break;
                }
                try {
                    StepOutcome outcome = completions.take().get();
                    if (outcome.succeeded()) {
                        succeeded.add(outcome.step().name());
                    } else {
                        failed = true;
                    }
                } catch (ExecutionException e) {
                    failed = true; // stop starting steps, let the running ones finish, then rethrow
                    if (workerError == null) {
                        workerError = e.getCause();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while running execution " + executionId, e);
                }
                running--;
            }
        }

        if (workerError instanceof SQLException e) {
            throw e;
        } else if (workerError instanceof RuntimeException e) {
            throw e;
        } else if (workerError != null) {
            throw new IllegalStateException("Step failed unexpectedly in execution " + executionId, workerError);
        }
        if (failed) {
            db.finishExecution(executionId, "FAILED");
        } else if (succeeded.size() == process.size()) {
            db.finishExecution(executionId, "SUCCEEDED");
        } else { // impossible for a valid DAG; fail loudly if not
            throw new IllegalStateException("Execution " + executionId + " stalled with steps " + succeeded + " done");
        }
    }

    /** Steps not started in this run whose dependencies have all succeeded, in definition order. */
    private List<StepDef> readySteps(Set<String> succeeded, Set<String> started) {
        return process.steps().stream()
                .filter(step -> !succeeded.contains(step.name()) && !started.contains(step.name()))
                .filter(step -> succeeded.containsAll(step.dependsOn()))
                .toList();
    }

    /** Runs one attempt of a step and records its outcome. Returns whether it succeeded. */
    private boolean runAttempt(long executionId, StepDef step, Simulation simulation) throws SQLException {
        int attemptNo = db.nextAttemptNo(executionId, step.index());
        long attemptId = db.insertRunningAttempt(executionId, step.index(), step.name(), attemptNo);
        try {
            simulateWork(step, attemptNo, simulation);
        } catch (RuntimeException e) {
            db.failAttempt(attemptId, e.getMessage(), stackTrace(e));
            return false;
        }
        db.finishAttempt(attemptId, "SUCCEEDED");
        return true;
    }

    /**
     * The simulated work of one step. A simulated failure happens immediately; a successful attempt
     * takes stepDelayMs. So when a step fails, its parallel siblings are reliably still running.
     */
    private static void simulateWork(StepDef step, int attemptNo, Simulation simulation) {
        if (simulation == null) {
            return;
        }
        if (simulation.failsOn(step.index(), attemptNo)) {
            throw new SimulatedStepFailure(step.name() + " failed (simulated failure "
                    + attemptNo + " of " + simulation.failTimes() + ")");
        }
        if (simulation.stepDelayMs() > 0) {
            try {
                Thread.sleep(simulation.stepDelayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SimulatedStepFailure(step.name() + " interrupted");
            }
        }
    }

    private static String stackTrace(Throwable t) {
        StringWriter out = new StringWriter();
        t.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    static class SimulatedStepFailure extends RuntimeException {
        SimulatedStepFailure(String message) {
            super(message);
        }
    }
}
