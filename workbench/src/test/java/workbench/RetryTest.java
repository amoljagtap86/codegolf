package workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RetryTest {

    private Db db;
    private Runner runner;

    @BeforeEach
    void setUp() throws Exception {
        db = TestDb.fresh();
        runner = new Runner(db);
    }

    @Test
    void retryResumesAtFailedStepWithoutRerunningEarlierSteps() throws Exception {
        long id = runner.start(Simulation.failing(3, 1));
        Execution failed = db.findExecution(id).orElseThrow();

        assertTrue(runner.retry(id));

        Execution execution = db.findExecution(id).orElseThrow();
        assertEquals("SUCCEEDED", execution.status());
        assertEquals(failed.startedAt(), execution.startedAt(), "started_at keeps the original start");
        assertNotNull(execution.finishedAt());
        assertEquals("1#1:SUCCEEDED 2#1:SUCCEEDED 3#1:FAILED 3#2:SUCCEEDED 4#1:SUCCEEDED 5#1:SUCCEEDED",
                timeline(execution));
        // The original failed attempt is history, kept as it was.
        assertEquals(failed.attempts().get(2), execution.attempts().get(2));
    }

    @Test
    void retryThatFailsAgainRecordsNewAttemptAndCanBeRetriedAgain() throws Exception {
        long id = runner.start(Simulation.failing(3, 2));

        assertTrue(runner.retry(id));
        Execution afterFirstRetry = db.findExecution(id).orElseThrow();
        assertEquals("FAILED", afterFirstRetry.status());
        assertEquals("1#1:SUCCEEDED 2#1:SUCCEEDED 3#1:FAILED 3#2:FAILED", timeline(afterFirstRetry));
        assertEquals("CHARGE_PAYMENT failed (simulated failure 2 of 2)",
                afterFirstRetry.attempts().get(3).errorMessage());

        assertTrue(runner.retry(id));
        Execution afterSecondRetry = db.findExecution(id).orElseThrow();
        assertEquals("SUCCEEDED", afterSecondRetry.status());
        assertEquals("1#1:SUCCEEDED 2#1:SUCCEEDED 3#1:FAILED 3#2:FAILED 3#3:SUCCEEDED 4#1:SUCCEEDED 5#1:SUCCEEDED",
                timeline(afterSecondRetry));
    }

    @Test
    void retryOfSucceededExecutionIsRejectedAndChangesNothing() throws Exception {
        long id = runner.start(null);
        Execution before = db.findExecution(id).orElseThrow();

        assertFalse(runner.retry(id));

        assertEquals(before, db.findExecution(id).orElseThrow());
    }

    @Test
    void repeatedRetryAfterSuccessfulRetryIsRejected() throws Exception {
        long id = runner.start(Simulation.failing(1, 1));
        assertTrue(runner.retry(id));
        Execution afterRetry = db.findExecution(id).orElseThrow();

        assertFalse(runner.retry(id));
        assertFalse(runner.retry(id));

        assertEquals(afterRetry, db.findExecution(id).orElseThrow());
    }

    @Test
    void retryOfUnknownExecutionIsRejected() throws Exception {
        assertFalse(runner.retry(999));
        assertNull(db.findExecution(999).orElse(null));
    }

    /**
     * Many threads released together retry the same FAILED execution; exactly one may run it.
     * A broken claim shows up as more than one winner, or as a thread failing on the
     * UNIQUE (execution_id, step_index, attempt_no) constraint.
     */
    @Test
    void concurrentRetriesRunExactlyOnce() throws Exception {
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 10; round++) {
                long id = runner.start(Simulation.failing(3, 1));
                CyclicBarrier barrier = new CyclicBarrier(threads);
                List<Future<Boolean>> results = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    results.add(pool.submit(() -> {
                        barrier.await();
                        return runner.retry(id);
                    }));
                }
                int winners = 0;
                for (Future<Boolean> result : results) {
                    if (result.get()) { // rethrows any exception from the retry
                        winners++;
                    }
                }
                assertEquals(1, winners, "round " + round);
                Execution execution = db.findExecution(id).orElseThrow();
                assertEquals("SUCCEEDED", execution.status(), "round " + round);
                assertEquals("1#1:SUCCEEDED 2#1:SUCCEEDED 3#1:FAILED 3#2:SUCCEEDED 4#1:SUCCEEDED 5#1:SUCCEEDED",
                        timeline(execution), "round " + round);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Compact attempt history, e.g. "1#1:SUCCEEDED 3#2:FAILED" = step#attempt:status, in order. */
    private static String timeline(Execution execution) {
        return execution.attempts().stream()
                .map(a -> a.stepIndex() + "#" + a.attemptNo() + ":" + a.status())
                .collect(Collectors.joining(" "));
    }
}
