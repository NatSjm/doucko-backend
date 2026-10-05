## MODIFIED Requirements

### Requirement: Data volume and backup

Postgres data SHALL live on the named Docker volume `doucko-pgdata`. A
documented `npm run backup` SHALL write a `pg_dump` of the database to a
file outside every repository (also when reached through a symlink or
junction), readable only by its owner (POSIX: directory 0700, file 0600; on
Windows: only under the user profile), and `npm run restore -- --yes <file>`
SHALL restore it, on Windows as well as on other platforms (NFR-10). Without
`--yes` restore changes nothing. Before dropping anything it SHALL check the
archive and take a safety backup, and it SHALL rebuild the schema from the
dump in a single transaction, so a failing dump leaves the data as it was and
no object from a newer schema survives an older dump. After the restore
committed it SHALL compare the newest applied Flyway version of the restored
schema with the newer of this checkout's newest migration and the version
the database had before the restore: when the restored schema is older and
the backend is running, it SHALL restart the backend, wait until it is healthy
and confirm the schema reached that version; when the backend is not running
it SHALL say that the next start migrates the database; when the restored
schema is newer it SHALL warn and restart nothing; a dump with tables but no
Flyway history SHALL get a warning and no restart. Whenever this check cannot
complete (version or backend state unreadable, restart or healthcheck failed,
schema still behind) it SHALL say that the data is restored, print the manual
step for the same compose file and project, and exit non-zero. Every restore
SHALL say that files on the `doucko-storage` volume are not restored.

#### Scenario: Backup survives losing the volume

- **GIVEN** a running database with one known row
- **WHEN** a backup is taken, the volume is removed, the stack is started again and the backup is restored
- **THEN** the known row is present

#### Scenario: A broken dump changes nothing

- **GIVEN** a running database with a known row and a truncated dump
- **WHEN** `restore --yes` is run with the truncated dump
- **THEN** it exits non-zero, the known row is still present, and a safety backup was written first

#### Scenario: An older dump over a newer schema

- **GIVEN** a dump taken before a table and a column were added
- **WHEN** it is restored
- **THEN** the dump's rows are back and the newer table and column are gone

#### Scenario: Unsafe backup targets

- **WHEN** the backup directory is inside a git working tree, directly or through a link, or (on Windows) outside the user profile
- **THEN** the backup is refused before anything is written


#### Scenario: A dump from before the newest migration under a running backend

- **GIVEN** the backend running at the newest migration and a dump taken before it
- **WHEN** `restore --yes` is run with that dump
- **THEN** the backend is restarted, the newest migration is applied again, the backend is healthy and serves the child area, and the output says that the `doucko-storage` files were not restored

#### Scenario: The restarted backend cannot migrate the restored schema

- **GIVEN** the backend running at the newest migration and a dump whose Flyway history ends before it while the newer tables already exist
- **WHEN** `restore --yes` is run with that dump
- **THEN** it exits non-zero, says that the data is restored and prints the `restart backend` and `logs backend` commands for the same compose project

#### Scenario: Restored schema and checkout disagree while no backend runs

- **GIVEN** no backend running
- **WHEN** an older dump is restored, or a dump newer than the checkout's migrations is restored
- **THEN** the restore says the next start migrates the database, or warns that the dump is newer, and restarts nothing
