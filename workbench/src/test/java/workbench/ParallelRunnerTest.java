package workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Parallel execution of the IC monthly run, whose four LOAD_* steps have no dependencies.
 * A step delay makes their overlap measurable.
 */
class ParallelRunnerTest {

    private static final int DELAY_MS = 300;
    private static final List<String> LOADS = List.of("LOAD_SALES_DATA", "LOAD_ALIGNMENT", "LOAD_ROSTER", "LOAD_GOALS");

    private Db db;
    private Runner runner;

    @BeforeEach
    void setUp() throws Exception {
        db = TestDb.fresh();
        runner = new Runner(db);
    }

    @Test
    void feedsLoadInParallelAndDependenciesAreRespected() throws Exception {
        long id = runner.start(new Simulation(null, null, DELAY_MS));

        Execution execution = db.findExecution(id).orElseThrow();
        assertEquals("SUCCEEDED", execution.status());
        // Overlap: every load started before every other load finished.
        for (String a : LOADS) {
            for (String b : LOADS) {
                assertTrue(attempt(execution, a, 1).startedAt().isBefore(attempt(execution, b, 1).finishedAt()),
                        a + " started after " + b + " finished");
            }
        }
        // CREDIT_SALES waited for its three feeds; COMPUTE_ATTAINMENT for crediting and goals.
        assertStartsAfter(execution, "CREDIT_SALES", "LOAD_SALES_DATA", "LOAD_ALIGNMENT", "LOAD_ROSTER");
        assertStartsAfter(execution, "COMPUTE_ATTAINMENT", "CREDIT_SALES", "LOAD_GOALS");
    }

    @Test
    void feedsRunningWhenOneFeedFailsCompleteAndNothingNewStarts() throws Exception {
        long id = runner.start(new Simulation(2, 1, DELAY_MS)); // LOAD_ALIGNMENT fails at once

        Execution execution = db.findExecution(id).orElseThrow();
        assertEquals("FAILED", execution.status());
        assertEquals("1#1:SUCCEEDED 2#1:FAILED 3#1:SUCCEEDED 4#1:SUCCEEDED", timeline(execution));

        StepAttempt alignment = attempt(execution, "LOAD_ALIGNMENT", 1);
        for (String load : List.of("LOAD_SALES_DATA", "LOAD_ROSTER", "LOAD_GOALS")) {
            StepAttempt other = attempt(execution, load, 1);
            // Mid-flight when LOAD_ALIGNMENT failed, and allowed to finish.
            assertTrue(other.startedAt().isBefore(alignment.finishedAt()), load);
            assertTrue(other.finishedAt().isAfter(alignment.finishedAt()), load);
            // The execution is marked FAILED only after the running feeds finished.
            assertFalse(execution.finishedAt().isBefore(other.finishedAt()), load);
        }
    }

    @Test
    void retryAfterParallelFailureReloadsOnlyTheFailedFeed() throws Exception {
        long id = runner.start(new Simulation(2, 1, DELAY_MS));

        assertTrue(runner.retry(id));

        Execution execution = db.findExecution(id).orElseThrow();
        assertEquals("SUCCEEDED", execution.status());
        // Sales, roster and goals are not reloaded.
        assertEquals("1#1:SUCCEEDED 2#1:FAILED 2#2:SUCCEEDED 3#1:SUCCEEDED 4#1:SUCCEEDED "
                + "5#1:SUCCEEDED 6#1:SUCCEEDED 7#1:SUCCEEDED 8#1:SUCCEEDED 9#1:SUCCEEDED", timeline(execution));
    }

    private static void assertStartsAfter(Execution execution, String step, String... dependencies) {
        StepAttempt attempt = attempt(execution, step, 1);
        for (String dependency : dependencies) {
            assertFalse(attempt.startedAt().isBefore(attempt(execution, dependency, 1).finishedAt()),
                    step + " started before " + dependency + " finished");
        }
    }

    private static StepAttempt attempt(Execution execution, String stepName, int attemptNo) {
        return execution.attempts().stream()
                .filter(a -> a.stepName().equals(stepName) && a.attemptNo() == attemptNo)
                .findFirst().orElseThrow();
    }

    private static String timeline(Execution execution) {
        return execution.attempts().stream()
                .map(a -> a.stepIndex() + "#" + a.attemptNo() + ":" + a.status())
                .collect(Collectors.joining(" "));
    }
}
