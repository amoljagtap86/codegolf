package workbench;

import java.time.OffsetDateTime;

public record StepAttempt(
        long id,
        int stepIndex,
        String stepName,
        int attemptNo,
        String status,
        String errorMessage,
        String errorDetail,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt) {
}
