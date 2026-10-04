-- child-profile (FR-43): the single child profile. The application creates the row on the first
-- start from DOUCKO_CHILD_DISPLAY_NAME; this migration holds no data (NFR-3: the name is never
-- committed). Append-only: later changes go into new migrations.
CREATE TABLE child_profile (
    -- At most one profile (store spec: exactly one learner, no profile selection).
    id                    integer      PRIMARY KEY CHECK (id = 1),
    -- 1 to 50 Unicode code points after trimming (varchar counts characters, not bytes).
    display_name          varchar(50)  NOT NULL CHECK (length(btrim(display_name)) > 0),
    -- A configured task locale, or NULL when the translation is off (the default).
    secondary_task_locale varchar(16),
    answer_mode           varchar(16)  NOT NULL DEFAULT 'DIRECT' CHECK (answer_mode IN ('DIRECT', 'GUIDED')),
    created_at            timestamptz  NOT NULL DEFAULT now()
);
