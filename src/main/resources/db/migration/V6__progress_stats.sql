-- progress-stats (slice 9): re-evaluation of a reported attempt and its XP (FR-41, FR-42, OQ-20).
-- A re-evaluation is recorded next to the attempt, never in place of it: the attempt log stays
-- append-only, and so does this table (the trigger below refuses UPDATE and DELETE). One row per attempt:
-- a recorded re-evaluation is always all-correct, so no later one can improve it.

CREATE TABLE attempt_reevaluation (
    attempt_id      uuid PRIMARY KEY REFERENCES attempt (id) ON DELETE RESTRICT,
    -- The report whose re-evaluation recorded this verdict.
    report_id       uuid NOT NULL REFERENCES problem_report (id) ON DELETE RESTRICT,
    -- {"<part key>": "CORRECT" | "WRONG"}: the verdict of every part under the answer spec of that moment.
    verdicts        jsonb NOT NULL,
    correct         boolean NOT NULL,
    reevaluated_at  timestamptz NOT NULL
);

CREATE FUNCTION attempt_reevaluation_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'attempt_reevaluation is append-only (FR-42)';
END;
$$;

CREATE TRIGGER attempt_reevaluation_append_only_trg
    BEFORE UPDATE OR DELETE ON attempt_reevaluation
    FOR EACH ROW EXECUTE FUNCTION attempt_reevaluation_append_only();

-- XP of a re-evaluation (FR-41, FR-38): a REEVALUATION row pays the difference between the new and the old
-- task award; a completion bonus it causes is a MISSION_BONUS row. Both carry the report, never an attempt,
-- so the child's attempt feedback and mission summaries keep what the attempt itself earned.
ALTER TABLE xp_award ADD COLUMN report_id uuid REFERENCES problem_report (id) ON DELETE RESTRICT;

ALTER TABLE xp_award DROP CONSTRAINT xp_award_kind_check;
ALTER TABLE xp_award ADD CONSTRAINT xp_award_kind_check CHECK (kind IN ('TASK', 'MISSION_BONUS', 'REEVALUATION'));

ALTER TABLE xp_award DROP CONSTRAINT xp_award_task_shape;
ALTER TABLE xp_award ADD CONSTRAINT xp_award_task_shape CHECK (
    (kind = 'TASK' AND task_id IS NOT NULL AND attempt_id IS NOT NULL AND report_id IS NULL)
    OR (kind = 'MISSION_BONUS' AND task_id IS NULL AND (report_id IS NULL OR attempt_id IS NULL))
    OR (kind = 'REEVALUATION' AND task_id IS NOT NULL AND attempt_id IS NULL AND report_id IS NOT NULL)
);

-- A report pays its re-evaluation once (re-evaluating twice awards XP once).
CREATE UNIQUE INDEX xp_award_reevaluation_once_uq ON xp_award (report_id) WHERE kind = 'REEVALUATION';

-- The flag queue: OPEN first, oldest first.
CREATE INDEX problem_report_queue_idx ON problem_report (status, created_at);
