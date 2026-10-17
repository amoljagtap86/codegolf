package workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Scheduler semantics on a small custom DAG in which A and C have no dependencies, so they start together:
 * <pre>
 *   A ─► B ─┐
 *           ├─► D
 *   C ──────┘
 * </pre>
 * Tests with a failure use a step delay: a simulated failure is immediate while a successful
 * attempt takes the delay, which makes "what was running when the failure happened" deterministic.
 */
class DagRunnerTest {

    private static final ProcessDefinition GRAPH = new ProcessDefinition(List.of(
            List.of("A"), List.of("B", "A"), List.of("C"), List.of("D", "B", "C")));
    private static final int DELAY_MS = 200;

    private Db db;
    private Runner runner;

    @BeforeEach
    void setUp() throws Exception {
        db = TestDb.fresh();
        runner = new Runner(db, GRAPH);
    }

    @Test
    void happyPathRunsEveryStepOnce() throws Exception {
        long id = runner.start(null);
        assertEquals("SUCCEEDED", db.findExecution(id).orElseThrow().status());
        assertEquals("A#1:SUCCEEDED B#1:SUCCEEDED C#1:SUCCEEDED D#1:SUCCEEDED", timeline(id));
    }

    @Test
    void stepAlreadyRunningWhenASiblingFailsCompletesButNothingDownstreamStarts() throws Exception {
        long id = runner.start(new Simulation(2, 1, DELAY_MS)); // B fails

        Execution execution = db.findExecution(id).orElseThrow();
        assertEquals("FAILED", execution.status());
        // C started together with A, long before B failed; it completes. D never starts.
        assertEquals("A#1:SUCCEEDED B#1:FAILED C#1:SUCCEEDED", timeline(id));
    }

    @Test
    void afterAFailureNoFurtherStepStartsEvenAnIndependentOne() throws Exception {
        long id = runner.start(new Simulation(3, 1, DELAY_MS)); // C fails at once, while A is running

        assertEquals("FAILED", db.findExecution(id).orElseThrow().status());
        // B became ready when A finished, after C had failed: failure policy A keeps it from starting.
        assertEquals("A#1:SUCCEEDED C#1:FAILED", timeline(id));
    }

    @Test
    void retryRunsEveryUnfinishedStepNotOnlyTheFailedOne() throws Exception {
        long id = runner.start(new Simulation(3, 1, DELAY_MS)); // as above: B never started

        assertTrue(runner.retry(id));

        assertEquals("SUCCEEDED", db.findExecution(id).orElseThrow().status());
        // A is not rerun; B gets its first attempt; C its second; then D.
        assertEquals("A#1:SUCCEEDED B#1:SUCCEEDED C#1:FAILED C#2:SUCCEEDED D#1:SUCCEEDED", timeline(id));
    }

    @Test
    void retryDoesNotRerunTheBranchThatSucceeded() throws Exception {
        long id = runner.start(new Simulation(2, 1, DELAY_MS)); // B fails; C succeeded meanwhile

        assertTrue(runner.retry(id));

        assertEquals("A#1:SUCCEEDED B#1:FAILED B#2:SUCCEEDED C#1:SUCCEEDED D#1:SUCCEEDED", timeline(id));
    }

    /** "A#1:SUCCEEDED" = step name # attempt : status, ordered by step then attempt. */
    private String timeline(long id) throws Exception {
        return db.findExecution(id).orElseThrow().attempts().stream()
                .map(a -> a.stepName() + "#" + a.attemptNo() + ":" + a.status())
                .collect(Collectors.joining(" "));
    }
}
