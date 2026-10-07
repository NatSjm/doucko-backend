-- gamification (slice 8): the append-only XP ledger and the played rank-up celebrations (FR-34, FR-35,
-- FR-36, FR-38). XP is never stored as a total: the total is the sum of the ledger, and a ledger row is
-- never changed or removed (the trigger below refuses UPDATE and DELETE). seq orders the ledger, so
-- "total before an award" is the sum of the earlier rows (rank-up detection).

CREATE TABLE xp_award (
    seq         bigserial PRIMARY KEY,
    id          uuid NOT NULL UNIQUE,
    kind        text NOT NULL CHECK (kind IN ('TASK', 'MISSION_BONUS')),
    -- TASK: the task resolved for the first time; null for a bonus.
    task_id     uuid REFERENCES task (id) ON DELETE RESTRICT,
    -- TASK: the mission the task was resolved through; MISSION_BONUS: the completed mission. No foreign
    -- key, like the attempt log: the ledger outlives later mission edits.
    mission_id  uuid NOT NULL,
    -- The resolution that caused the row; null only for a bonus credited when a mission was published
    -- already complete (OQ-28).
    attempt_id  uuid REFERENCES attempt (id) ON DELETE RESTRICT,
    amount      integer NOT NULL CHECK (amount BETWEEN 0 AND 1000),
    created_at  timestamptz NOT NULL,
    CONSTRAINT xp_award_task_shape CHECK (
        (kind = 'TASK' AND task_id IS NOT NULL AND attempt_id IS NOT NULL)
        OR (kind = 'MISSION_BONUS' AND task_id IS NULL)
    )
);

-- A task earns XP once (OQ-15); a mission pays its bonus once (OQ-17, OQ-28).
CREATE UNIQUE INDEX xp_award_task_once_uq ON xp_award (task_id) WHERE kind = 'TASK';
CREATE UNIQUE INDEX xp_award_bonus_once_uq ON xp_award (mission_id) WHERE kind = 'MISSION_BONUS';
CREATE INDEX xp_award_attempt_idx ON xp_award (attempt_id);

CREATE FUNCTION xp_award_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'xp_award is append-only (FR-38)';
END;
$$;

CREATE TRIGGER xp_award_append_only_trg
    BEFORE UPDATE OR DELETE ON xp_award
    FOR EACH ROW EXECUTE FUNCTION xp_award_append_only();

-- A played rank-up celebration (FR-36): the highest stored rank_index is "celebrated"; HqView.pendingRankUp
-- is the current rank while it is higher. Insert-only.
CREATE TABLE rank_celebration (
    id             bigserial PRIMARY KEY,
    rank_index     integer NOT NULL CHECK (rank_index >= 0),
    celebrated_at  timestamptz NOT NULL
);
