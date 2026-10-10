package workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Scheduler semantics on a small custom DAG where C is independent of the failing branch:
 * <pre>
 *   A ─► B ─┐
 *           ├─► D
 *   C ──────┘
 * </pre>
 */
class DagRunnerTest {

    private static final ProcessDefinition GRAPH = new ProcessDefinition(List.of(
            List.of("A"), List.of("B", "A"), List.of("C"), List.of("D", "B", "C")));

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
    void afterAFailureNoFurtherStepStartsEvenAnIndependentOne() throws Exception {
        long id = runner.start(new FailurePlan(2, 1)); // B fails

        assertEquals("FAILED", db.findExecution(id).orElseThrow().status());
        // C was ready (no dependencies) but must not start: failure policy A.
        assertEquals("A#1:SUCCEEDED B#1:FAILED", timeline(id));
    }

    @Test
    void retryRunsEveryUnfinishedStepNotOnlyTheFailedOne() throws Exception {
        long id = runner.start(new FailurePlan(2, 1));

        assertTrue(runner.retry(id));

        assertEquals("SUCCEEDED", db.findExecution(id).orElseThrow().status());
        // A is not rerun; B gets attempt 2; C, never started, gets its first attempt; then D.
        assertEquals("A#1:SUCCEEDED B#1:FAILED B#2:SUCCEEDED C#1:SUCCEEDED D#1:SUCCEEDED", timeline(id));
    }

    @Test
    void retryWhenTheIndependentBranchFailedDoesNotRerunTheOtherBranch() throws Exception {
        long id = runner.start(new FailurePlan(3, 1)); // C fails; A and B already succeeded
        assertEquals("A#1:SUCCEEDED B#1:SUCCEEDED C#1:FAILED", timeline(id));

        assertTrue(runner.retry(id));

        assertEquals("A#1:SUCCEEDED B#1:SUCCEEDED C#1:FAILED C#2:SUCCEEDED D#1:SUCCEEDED", timeline(id));
    }

    /** "A#1:SUCCEEDED" = step name # attempt : status, ordered by step then attempt. */
    private String timeline(long id) throws Exception {
        return db.findExecution(id).orElseThrow().attempts().stream()
                .map(a -> a.stepName() + "#" + a.attemptNo() + ":" + a.status())
                .collect(Collectors.joining(" "));
    }
}
