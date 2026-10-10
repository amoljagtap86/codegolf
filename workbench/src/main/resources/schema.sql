-- Applied at startup. Idempotent: safe to run on every boot.

CREATE TABLE IF NOT EXISTS executions (
    id           bigserial   PRIMARY KEY,
    status       text        NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    failure_plan jsonb,      -- used from milestone 2 (failure injection); null = happy path
    created_at   timestamptz NOT NULL DEFAULT now(),
    started_at   timestamptz,
    finished_at  timestamptz
);

-- Append-only: one row per attempt of a step. Step status is derived from its latest attempt.
CREATE TABLE IF NOT EXISTS step_attempts (
    id            bigserial   PRIMARY KEY,
    execution_id  bigint      NOT NULL REFERENCES executions (id),
    step_index    int         NOT NULL CHECK (step_index BETWEEN 1 AND 5),
    step_name     text        NOT NULL,
    attempt_no    int         NOT NULL CHECK (attempt_no >= 1),
    status        text        NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    error_message text,
    error_detail  text,
    started_at    timestamptz NOT NULL DEFAULT now(),
    finished_at   timestamptz,
    UNIQUE (execution_id, step_index, attempt_no)
);
