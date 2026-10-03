# Fixture provenance (NFR-4, CA-D21)

Every fixture file in this repository (`src/test/resources/**`, `fixtures/**`)
is listed here. Workbook content (text, images, answer keys, PDFs) is never
committed: every fixture is self-authored. `FixtureProvenanceTest` fails on an
unlisted file, an origin other than `self-authored`, a missing author, or a
listed file that does not exist.

| Path | Origin | Author | What it is |
|---|---|---|---|
