package workbench;

import java.time.OffsetDateTime;

/** One row of the execution history list. retryCount = attempts beyond the first per step. */
public record ExecutionSummary(
        long id,
        String status,
        FailurePlan failurePlan,
        OffsetDateTime createdAt,
        OffsetDateTime finishedAt,
        int attemptCount,
        int retryCount) {
}
