package workbench;

import java.time.OffsetDateTime;
import java.util.List;

public record Execution(
        long id,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        List<StepAttempt> attempts) {
}
