package workbench;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import workbench.ProcessDefinition.StepDef;

/**
 * Runs a process DAG, one step at a time (milestone 5a). A step is ready when all its dependencies
 * have succeeded and it has not; the runner keeps running a ready step until none is left.
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
     * @param plan simulated failure, or null for the happy path
     */
    public long start(FailurePlan plan) throws SQLException {
        long executionId = db.insertRunningExecution(plan);
        run(executionId, plan);
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
        run(executionId, db.findExecution(executionId).orElseThrow().failurePlan());
        return true;
    }

    /**
     * Runs ready steps, in definition order, until none is left. Failure policy: once a step fails,
     * no further step starts, even an independent one, and the execution fails. Database errors are
     * not caught.
     */
    private void run(long executionId, FailurePlan plan) throws SQLException {
        Set<String> succeeded = new HashSet<>();
        for (int index : db.succeededSteps(executionId)) {
            succeeded.add(process.step(index).name());
        }
        Optional<StepDef> next;
        while ((next = nextReadyStep(succeeded)).isPresent()) {
            if (!runAttempt(executionId, next.get(), plan)) {
                db.finishExecution(executionId, "FAILED");
                return;
            }
            succeeded.add(next.get().name());
        }
        if (succeeded.size() != process.size()) { // impossible for a valid DAG; fail loudly if not
            throw new IllegalStateException("Execution " + executionId + " stalled with steps " + succeeded + " done");
        }
        db.finishExecution(executionId, "SUCCEEDED");
    }

    private Optional<StepDef> nextReadyStep(Set<String> succeeded) {
        return process.steps().stream()
                .filter(step -> !succeeded.contains(step.name()))
                .filter(step -> succeeded.containsAll(step.dependsOn()))
                .findFirst();
    }

    /** Runs one attempt of a step and records its outcome. Returns whether it succeeded. */
    private boolean runAttempt(long executionId, StepDef step, FailurePlan plan) throws SQLException {
        int attemptNo = db.nextAttemptNo(executionId, step.index());
        long attemptId = db.insertRunningAttempt(executionId, step.index(), step.name(), attemptNo);
        try {
            simulateWork(step, attemptNo, plan);
        } catch (RuntimeException e) {
            db.failAttempt(attemptId, e.getMessage(), stackTrace(e));
            return false;
        }
        db.finishAttempt(attemptId, "SUCCEEDED");
        return true;
    }

    /** The simulated work of one step: does nothing unless the plan says this attempt fails. */
    private static void simulateWork(StepDef step, int attemptNo, FailurePlan plan) {
        if (plan != null && plan.failsOn(step.index(), attemptNo)) {
            throw new SimulatedStepFailure(step.name() + " failed (simulated failure "
                    + attemptNo + " of " + plan.failTimes() + ")");
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
