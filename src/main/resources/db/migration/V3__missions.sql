-- content-authoring (slice 6): missions, an ordered list of tasks of one topic (FR-9).
-- A mission has a topic and a position unique within that topic, and no name (CA-D17). A task appears
-- at most once in one mission (CA-D12) and may belong to several missions (OQ-15).

CREATE TABLE mission (
    id          uuid PRIMARY KEY,
    topic_id    uuid NOT NULL REFERENCES topic (id),
    position    integer NOT NULL CHECK (position BETWEEN 1 AND 999),
    status      text NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT mission_topic_position_uq UNIQUE (topic_id, position)
);

CREATE TABLE mission_task (
    mission_id  uuid NOT NULL REFERENCES mission (id) ON DELETE CASCADE,
    task_id     uuid NOT NULL REFERENCES task (id) ON DELETE RESTRICT,
    position    integer NOT NULL CHECK (position BETWEEN 1 AND 50),
    PRIMARY KEY (mission_id, task_id),
    CONSTRAINT mission_task_position_uq UNIQUE (mission_id, position) DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX mission_task_task_idx ON mission_task (task_id);
