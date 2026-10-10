package workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        long id = runner.start();

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
            assertEquals(Runner.STEPS.get(i), attempt.stepName());
            assertEquals(1, attempt.attemptNo());
            assertEquals("SUCCEEDED", attempt.status());
            assertNull(attempt.errorMessage());
            assertNotNull(attempt.finishedAt());
            if (i > 0) {
                // Sequential: a step never starts before the previous one finished.
                assertFalse(attempt.startedAt().isBefore(attempts.get(i - 1).finishedAt()));
            }
        }
    }

    @Test
    void unknownExecutionIsEmpty() throws Exception {
        assertEquals(java.util.Optional.empty(), db.findExecution(999));
    }
}
