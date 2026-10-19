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
    void normalExecutionRunsEveryStepOnceRespectingDependencies() throws Exception {
        long id = runner.start(null);

        Execution execution = db.findExecution(id).orElseThrow();
        assertEquals("SUCCEEDED", execution.status());
        assertNotNull(execution.startedAt());
        assertNotNull(execution.finishedAt());
        assertFalse(execution.finishedAt().isBefore(execution.startedAt()));

        List<StepAttempt> attempts = execution.attempts();
        assertEquals(ProcessDefinition.IC_MONTHLY_RUN.size(), attempts.size());
        for (int i = 0; i < attempts.size(); i++) {
            StepAttempt attempt = attempts.get(i);
            assertEquals(i + 1, attempt.stepIndex());
            assertEquals(ProcessDefinition.IC_MONTHLY_RUN.step(i + 1).name(), attempt.stepName());
            assertEquals(1, attempt.attemptNo());
            assertEquals("SUCCEEDED", attempt.status());
            assertNull(attempt.errorMessage());
            assertNotNull(attempt.finishedAt());
            // DAG order: a step never starts before all of its dependencies finished.
            for (String dependency : ProcessDefinition.IC_MONTHLY_RUN.step(i + 1).dependsOn()) {
                StepAttempt dep = attempts.stream().filter(a -> a.stepName().equals(dependency)).findFirst().orElseThrow();
                assertFalse(attempt.startedAt().isBefore(dep.finishedAt()), attempt.stepName() + " before " + dependency);
            }
        }
    }

    @Test
    void failedStepFailsExecutionAndLaterStepsAreNotAttempted() throws Exception {
        long id = runner.start(Simulation.failing(5, 1)); // CREDIT_SALES, after all four loads

        Execution execution = db.findExecution(id).orElseThrow();
        assertEquals("FAILED", execution.status());
        assertNotNull(execution.finishedAt());
        assertEquals(Simulation.failing(5, 1), execution.simulation());

        List<StepAttempt> attempts = execution.attempts();
        assertEquals(5, attempts.size(), "steps after CREDIT_SALES must have no attempts");
        for (int i = 0; i < 4; i++) {
            assertEquals("SUCCEEDED", attempts.get(i).status(), attempts.get(i).stepName());
        }

        StepAttempt failed = attempts.get(4);
        assertEquals(5, failed.stepIndex());
        assertEquals("CREDIT_SALES", failed.stepName());
        assertEquals(1, failed.attemptNo());
        assertEquals("FAILED", failed.status());
        assertNotNull(failed.finishedAt());
        assertEquals("CREDIT_SALES failed (simulated failure 1 of 1)", failed.errorMessage());
        assertTrue(failed.errorDetail().contains("SimulatedStepFailure"), failed.errorDetail());
    }

    @Test
    void unknownExecutionIsEmpty() throws Exception {
        assertEquals(java.util.Optional.empty(), db.findExecution(999));
    }
}
