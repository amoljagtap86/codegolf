package workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RunnerTest {

    private Db db;
    private Runner runner;

    @BeforeEach
    void setUp() throws Exception {
        db = TestDb.fresh();
        runner = new Runner(db);
    }

    @Test
    void normalExecutionRunsAllFiveStepsOnceInOrder() throws Exception {
        long id = runner.start(null);

        Execution execution = db.findExecution(id).orElseThrow();
        assertEquals("SUCCEEDED", execution.status());
        assertNotNull(execution.startedAt());
        assertNotNull(execution.finishedAt());
        assertFalse(execution.finishedAt().isBefore(execution.startedAt()));

        List<StepAttempt> attempts = execution.attempts();
        assertEquals(5, attempts.size());
        for (int i = 0; i < attempts.size(); i++) {
            StepAttempt attempt = attempts.get(i);
            assertEquals(i + 1, attempt.stepIndex());
            assertEquals(ProcessDefinition.ORDER.step(i + 1).name(), attempt.stepName());
            assertEquals(1, attempt.attemptNo());
            assertEquals("SUCCEEDED", attempt.status());
            assertNull(attempt.errorMessage());
            assertNotNull(attempt.finishedAt());
            // DAG order: a step never starts before all of its dependencies finished.
            for (String dependency : ProcessDefinition.ORDER.step(i + 1).dependsOn()) {
                StepAttempt dep = attempts.stream().filter(a -> a.stepName().equals(dependency)).findFirst().orElseThrow();
                assertFalse(attempt.startedAt().isBefore(dep.finishedAt()), attempt.stepName() + " before " + dependency);
            }
        }
    }

    @Test
    void failedStepFailsExecutionAndLaterStepsAreNotAttempted() throws Exception {
        long id = runner.start(new FailurePlan(3, 1));

        Execution execution = db.findExecution(id).orElseThrow();
        assertEquals("FAILED", execution.status());
        assertNotNull(execution.finishedAt());
        assertEquals(new FailurePlan(3, 1), execution.failurePlan());

        List<StepAttempt> attempts = execution.attempts();
        assertEquals(3, attempts.size(), "steps 4 and 5 must have no attempts");
        assertEquals("SUCCEEDED", attempts.get(0).status());
        assertEquals("SUCCEEDED", attempts.get(1).status());

        StepAttempt failed = attempts.get(2);
        assertEquals(3, failed.stepIndex());
        assertEquals("CHARGE_PAYMENT", failed.stepName());
        assertEquals(1, failed.attemptNo());
        assertEquals("FAILED", failed.status());
        assertNotNull(failed.finishedAt());
        assertEquals("CHARGE_PAYMENT failed (simulated failure 1 of 1)", failed.errorMessage());
        assertTrue(failed.errorDetail().contains("SimulatedStepFailure"), failed.errorDetail());
    }

    @Test
    void unknownExecutionIsEmpty() throws Exception {
        assertEquals(java.util.Optional.empty(), db.findExecution(999));
    }
}
