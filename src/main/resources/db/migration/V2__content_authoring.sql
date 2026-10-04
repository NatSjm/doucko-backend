-- content-authoring (slice 5): seeded topics, PDF sources, tasks and task images.
-- Self-authored seed data (fixtures/PROVENANCE.md): grade-6 thematic areas of the Czech curriculum
-- (RVP ZV), not workbook chapters (OQ-2, OQ-21). No workbook content (NFR-4).

CREATE TABLE topic (
    id        uuid PRIMARY KEY,
    code      text NOT NULL UNIQUE,
    grade     integer NOT NULL,
    name      text NOT NULL,
    position  integer NOT NULL
);

INSERT INTO topic (id, code, grade, name, position) VALUES
    ('6a000000-0000-4000-8000-000000000001', 'g6-natural-numbers', 6, 'Přirozená čísla', 1),
    ('6a000000-0000-4000-8000-000000000002', 'g6-decimals', 6, 'Desetinná čísla', 2),
    ('6a000000-0000-4000-8000-000000000003', 'g6-divisibility', 6, 'Dělitelnost přirozených čísel', 3),
    ('6a000000-0000-4000-8000-000000000004', 'g6-angles', 6, 'Úhly', 4),
    ('6a000000-0000-4000-8000-000000000005', 'g6-axial-symmetry', 6, 'Osová souměrnost', 5),
    ('6a000000-0000-4000-8000-000000000006', 'g6-triangles', 6, 'Trojúhelník', 6),
    ('6a000000-0000-4000-8000-000000000007', 'g6-cuboid-cube', 6, 'Krychle a kvádr', 7),
    ('6a000000-0000-4000-8000-000000000008', 'g6-units', 6, 'Jednotky a jejich převody', 8),
    ('6a000000-0000-4000-8000-000000000009', 'g6-word-problems', 6, 'Slovní úlohy', 9);

CREATE TABLE source (
    id           uuid PRIMARY KEY,
    title        text NOT NULL,
    publisher    text,
    edition      text,
    grade        integer NOT NULL,
    subject      text NOT NULL,
    page_count   integer NOT NULL CHECK (page_count > 0),
    storage_key  text NOT NULL UNIQUE,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE task (
    id             uuid PRIMARY KEY,
    prompt         text NOT NULL,
    prompt_folded  text NOT NULL,
    topic_id       uuid NOT NULL REFERENCES topic (id),
    grade          integer NOT NULL,
    difficulty     integer NOT NULL CHECK (difficulty BETWEEN 1 AND 3),
    modality       text NOT NULL CHECK (modality IN ('TEXT', 'GRAPHIC')),
    status         text NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED')),
    source_id      uuid NOT NULL REFERENCES source (id) ON DELETE RESTRICT,
    page           integer NOT NULL CHECK (page > 0),
    task_no        text NOT NULL,
    sub_part       text,
    parts          jsonb NOT NULL,
    hints          jsonb NOT NULL,
    translations   jsonb NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX task_source_page_idx ON task (source_id, page);
CREATE INDEX task_topic_idx ON task (topic_id);

CREATE TABLE task_image (
    id           uuid PRIMARY KEY,
    task_id      uuid NOT NULL REFERENCES task (id) ON DELETE CASCADE,
    position     integer NOT NULL CHECK (position > 0),
    media_type   text NOT NULL,
    storage_key  text NOT NULL UNIQUE,
    CONSTRAINT task_image_position_uq UNIQUE (task_id, position) DEFERRABLE INITIALLY DEFERRED
);
