# Fixture provenance (NFR-4, CA-D21)

Every fixture file in this repository (`src/test/resources/**`,
`src/main/resources/**`, `fixtures/**` and non-Kotlin files under
`src/test/kotlin/`) is listed here; only application config, the message
catalog and Flyway migrations are exempt. Workbook content (text, images, answer keys, PDFs) is never
committed: every fixture is self-authored. `FixtureProvenanceTest` fails on an
unlisted file, an origin other than `self-authored`, a missing author, a
listed file that does not exist or lies outside those roots, or a row that
does not have the shape below.

| Path | Origin | Author | What it is |
|---|---|---|---|
| `src/test/resources/application.properties` | self-authored | Doučko maintainers | Test-only `ADMIN_PASSWORD`, the example value from the admin-access spec scenarios. Not a real password. Also the fictional `DOUCKO_CHILD_DISPLAY_NAME` (`Hrdinka`) of the child-profile spec scenarios, never a real name. |
| `src/test/resources/checking/golden-table.jsonl` | self-authored | Doučko maintainers | Answer-checker golden table (FR-23): answer specs, raw inputs and expected verdicts written for the answer-checking spec scenarios. No workbook content. |
| `src/main/resources/db/migration/V2__content_authoring.sql` | self-authored | Doučko maintainers | content-authoring schema and the seeded grade-6 topic list: names of the grade-6 thematic areas of the Czech curriculum (RVP ZV), written for this project, not taken from any workbook (OQ-2, OQ-21). |
| `src/main/resources/gamification.json` | self-authored | Doučko maintainers | gamification configuration (FR-34..FR-36, FR-39): the default XP values of the gamification spec and the placeholder rank ladder (0, 150, 400, 800, 1500 XP) with original placeholder hero-academy rank names written for this project (OQ-1), checked against the IP deny-list. |
| `src/test/resources/gamification-alternate.json` | self-authored | Doučko maintainers | A second gamification configuration for `GamificationConfigIT`: other XP values and other original rank names (skin swap, two thresholds crossed by one award). |
