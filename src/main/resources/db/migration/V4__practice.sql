-- practice-flow (slice 7): replay runs, the attempt log, opened hints and problem reports (FR-27, FR-28,
-- FR-30, FR-31, FR-42). Practice state lives per task and scope: the primary scope is the nil UUID (the
-- first play-through, shared by every mission that contains the task, OQ-15); a replay run is its own
-- scope. Rows are only ever inserted: the attempt log is append-only (FR-42, OQ-20).
-- No foreign key on mission_id / scope: the log outlives later mission edits. The task foreign keys make
-- the FR-56 delete guard a database rule too.

CREATE TABLE practice_run (
    id          uuid PRIMARY KEY,
    mission_id  uuid NOT NULL REFERENCES mission (id) ON DELETE CASCADE,
    started_at  timestamptz NOT NULL
);

CREATE INDEX practice_run_mission_idx ON practice_run (mission_id, started_at);

CREATE TABLE attempt (
    id          uuid PRIMARY KEY,
    task_id     uuid NOT NULL REFERENCES task (id) ON DELETE RESTRICT,
    mission_id  uuid NOT NULL,
    scope       uuid NOT NULL,
    replay      boolean NOT NULL,
    attempt_no  integer NOT NULL CHECK (attempt_no BETWEEN 1 AND 2),
    hints_used  integer NOT NULL CHECK (hints_used >= 0),
    correct     boolean NOT NULL,
    -- [{"key","raw" (string | array | null when locked),"normalized","verdict","locked"}]
    parts       jsonb NOT NULL,
    created_at  timestamptz NOT NULL,
    CONSTRAINT attempt_task_scope_no_uq UNIQUE (task_id, scope, attempt_no)
);

CREATE INDEX attempt_scope_task_idx ON attempt (scope, task_id);

CREATE TABLE opened_hint (
    task_id     uuid NOT NULL REFERENCES task (id) ON DELETE RESTRICT,
    scope       uuid NOT NULL,
    hint_index  integer NOT NULL CHECK (hint_index >= 1),
    mission_id  uuid NOT NULL,
    opened_at   timestamptz NOT NULL,
    PRIMARY KEY (task_id, scope, hint_index)
);

CREATE INDEX opened_hint_scope_idx ON opened_hint (scope);

CREATE TABLE problem_report (
    id          uuid PRIMARY KEY,
    task_id     uuid NOT NULL REFERENCES task (id) ON DELETE RESTRICT,
    mission_id  uuid NOT NULL,
    reason      text NOT NULL CHECK (reason IN ('ANSWER_IS_CORRECT', 'UNCLEAR_PROMPT', 'OTHER')),
    note        text,
    attempt_id  uuid REFERENCES attempt (id) ON DELETE RESTRICT,
    status      text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'FIXED', 'DISMISSED')),
    created_at  timestamptz NOT NULL
);

CREATE INDEX problem_report_task_idx ON problem_report (task_id);
