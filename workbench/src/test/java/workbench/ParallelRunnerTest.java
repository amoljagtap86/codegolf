package workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Parallel execution of the order process, where RESERVE_INVENTORY (2) and CHARGE_PAYMENT (3)
 * both depend only on VALIDATE_ORDER. A step delay makes their overlap measurable.
 */
class ParallelRunnerTest {

    private static final int DELAY_MS = 300;

    private Db db;
    private Runner runner;

    @BeforeEach
    void setUp() throws Exception {
        db = TestDb.fresh();
        runner = new Runner(db);
    }

    @Test
    void independentStepsRunInParallelAndDependenciesAreRespected() throws Exception {
        long id = runner.start(new Simulation(null, null, DELAY_MS));

        Execution execution = db.findExecution(id).orElseThrow();
        assertEquals("SUCCEEDED", execution.status());
        StepAttempt reserve = attempt(execution, "RESERVE_INVENTORY", 1);
        StepAttempt charge = attempt(execution, "CHARGE_PAYMENT", 1);
        // Overlap: each started before the other finished.
        assertTrue(reserve.startedAt().isBefore(charge.finishedAt()), "reserve started after charge finished");
        assertTrue(charge.startedAt().isBefore(reserve.finishedAt()), "charge started after reserve finished");
        // CREATE_SHIPMENT waited for both.
        StepAttempt shipment = attempt(execution, "CREATE_SHIPMENT", 1);
        assertFalse(shipment.startedAt().isBefore(reserve.finishedAt()));
        assertFalse(shipment.startedAt().isBefore(charge.finishedAt()));
    }

    @Test
    void siblingRunningWhenAStepFailsCompletesAndNothingNewStarts() throws Exception {
        long id = runner.start(new Simulation(2, 1, DELAY_MS)); // RESERVE_INVENTORY fails at once

        Execution execution = db.findExecution(id).orElseThrow();
        assertEquals("FAILED", execution.status());
        assertEquals("1#1:SUCCEEDED 2#1:FAILED 3#1:SUCCEEDED", timeline(execution));

        StepAttempt reserve = attempt(execution, "RESERVE_INVENTORY", 1);
        StepAttempt charge = attempt(execution, "CHARGE_PAYMENT", 1);
        // CHARGE_PAYMENT was mid-flight when RESERVE_INVENTORY failed, and was allowed to finish.
        assertTrue(charge.startedAt().isBefore(reserve.finishedAt()));
        assertTrue(charge.finishedAt().isAfter(reserve.finishedAt()));
        // The execution is marked FAILED only after the running sibling finished.
        assertFalse(execution.finishedAt().isBefore(charge.finishedAt()));
    }

    @Test
    void retryAfterParallelFailureRunsOnlyTheFailedBranchAndWhatDependsOnIt() throws Exception {
        long id = runner.start(new Simulation(2, 1, DELAY_MS));

        assertTrue(runner.retry(id));

        Execution execution = db.findExecution(id).orElseThrow();
        assertEquals("SUCCEEDED", execution.status());
        // CHARGE_PAYMENT is not charged twice.
        assertEquals("1#1:SUCCEEDED 2#1:FAILED 2#2:SUCCEEDED 3#1:SUCCEEDED 4#1:SUCCEEDED 5#1:SUCCEEDED",
                timeline(execution));
    }

    private static StepAttempt attempt(Execution execution, String stepName, int attemptNo) {
        return execution.attempts().stream()
                .filter(a -> a.stepName().equals(stepName) && a.attemptNo() == attemptNo)
                .findFirst().orElseThrow();
    }

    private static String timeline(Execution execution) {
        List<StepAttempt> attempts = execution.attempts();
        return attempts.stream()
                .map(a -> a.stepIndex() + "#" + a.attemptNo() + ":" + a.status())
                .collect(Collectors.joining(" "));
    }
}
