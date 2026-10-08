## ADDED Requirements

### Requirement: Isolated end-to-end stack

The backend repository SHALL provide `node tools/e2e-stack.mjs up|down|seed` that runs the
Compose stack as the separate project `doucko-e2e` with its own volumes, publishing only the
frontend on `127.0.0.1:18082` (NFR-2), configured only from committed, clearly fake test values
and never from `.env` (NFR-3). `down` SHALL remove that project's containers and volumes and
SHALL NOT address any other Compose project. `seed` SHALL, through the public admin API,
create what is missing of one generated blank-page PDF source, published self-authored tasks of
at least two answer types and one published mission (NFR-4), and running it again SHALL leave
the same state.

#### Scenario: Down never reaches the owner's stack

- **GIVEN** the environment sets `COMPOSE_PROJECT_NAME=doucko`
- **WHEN** `node tools/e2e-stack.mjs down --dry-run` is run
- **THEN** the docker arguments name project `doucko-e2e` and no other project

#### Scenario: Seed is idempotent

- **GIVEN** a running e2e stack that was seeded once
- **WHEN** `seed` runs again
- **THEN** the admin API lists exactly one seed source, the same three published tasks and one published mission
