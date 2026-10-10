package workbench;

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

    /** Creates an execution and runs all steps synchronously. Returns the execution id. */
    public long start() throws SQLException {
        long executionId = db.insertRunningExecution();
        for (int i = 0; i < STEPS.size(); i++) {
            long attemptId = db.insertRunningAttempt(executionId, i + 1, STEPS.get(i), 1);
            // Simulated work goes here; failure injection arrives in milestone 2.
            db.finishAttempt(attemptId, "SUCCEEDED");
        }
        db.finishExecution(executionId, "SUCCEEDED");
        return executionId;
    }
}
