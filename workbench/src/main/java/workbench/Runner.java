package workbench;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;
import java.util.List;

/** Runs the simulated business process: five sequential steps. */
public class Runner {

    public static final List<String> STEPS = List.of(
            "VALIDATE_ORDER",
            "RESERVE_INVENTORY",
            "CHARGE_PAYMENT",
            "CREATE_SHIPMENT",
            "SEND_CONFIRMATION");

    private final Db db;

    public Runner(Db db) {
        this.db = db;
    }

    /**
     * Creates an execution and runs its steps synchronously until one fails or all succeed.
     * A step failure is recorded on its attempt and fails the execution; later steps are not
     * attempted. Database errors are not caught. Returns the execution id.
     *
     * @param plan simulated failure, or null for the happy path
     */
    public long start(FailurePlan plan) throws SQLException {
        long executionId = db.insertRunningExecution(plan);
        for (int i = 0; i < STEPS.size(); i++) {
            int stepIndex = i + 1;
            int attemptNo = 1;
            long attemptId = db.insertRunningAttempt(executionId, stepIndex, STEPS.get(i), attemptNo);
            try {
                runStep(stepIndex, attemptNo, plan);
            } catch (RuntimeException e) {
                db.failAttempt(attemptId, e.getMessage(), stackTrace(e));
                db.finishExecution(executionId, "FAILED");
                return executionId;
            }
            db.finishAttempt(attemptId, "SUCCEEDED");
        }
        db.finishExecution(executionId, "SUCCEEDED");
        return executionId;
    }

    /** The simulated work of one step: does nothing unless the plan says this attempt fails. */
    private static void runStep(int stepIndex, int attemptNo, FailurePlan plan) {
        if (plan != null && plan.failsOn(stepIndex, attemptNo)) {
            throw new SimulatedStepFailure(STEPS.get(stepIndex - 1) + " failed (simulated failure "
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
