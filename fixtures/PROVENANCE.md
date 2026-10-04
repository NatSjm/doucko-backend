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
