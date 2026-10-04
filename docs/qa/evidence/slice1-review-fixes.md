# Slice 1 review-gate fixes — red, green, mutation (2026-10-04)

Review gate `trace/workflow-runs/20261003-233104-review-gate.json` (first run with
PD-13): 23 confirmed + 3 contested findings, deduplicated into the fixes below.
Tests were written first and run before any implementation change.

## Red (tests only, implementation unchanged)

```
[ERROR]   BindAddressTest.the default compose binding passes the launcher check:63 expected:<0> but was:<2>
[ERROR]   BindAddressTest.the launcher checks the binding compose will use, not its own reading of the env file:56 expected:<1> but was:<2>
[ERROR]   BindAddressTest.wildcard, public and malformed addresses are refused:46 expected:<1> but was:<0>
[ERROR]   BindAddressTest.wildcard, public and malformed addresses are refused:46 expected:<1> but was:<0>
[ERROR]   RequestContentNotLoggedTest.a bound body, query and header values stay out of the logs at the configured levels(CapturedOutput) Status expected:<204> but was:<403>
[ERROR]   RequestContentNotLoggedTest.the capture sees a bound body when Spring MVC logs at DEBUG(CapturedOutput) Status expected:<204> but was:<403>
[ERROR]   UndeclaredPathTest.a write to an undeclared path needs no CSRF token and is 404 problem+json:87->isProblem:59->isProblem$lambda$0:60->isProblem$lambda$0$0:60 Response status expected:<404> but was:<403>
[ERROR]   UndeclaredPathTest.a write to an undeclared path needs no CSRF token and is 404 problem+json:87->isProblem:59->isProblem$lambda$0:60->isProblem$lambda$0$0:60 Response status expected:<404> but was:<403>
[ERROR]   UndeclaredPathTest.a write to an undeclared path needs no CSRF token and is 404 problem+json:87->isProblem:59->isProblem$lambda$0:60->isProblem$lambda$0$0:60 Response status expected:<404> but was:<403>
[ERROR]   UndeclaredPathTest.the admin area is closed until admin-access, as problem+json, and no handler runs:97->title:54 � NoSuchMessage No message found under code 'problemDetail.title.org.springframework.security.ac
[ERROR]   UndeclaredPathTest.the admin area is closed until admin-access, as problem+json, and no handler runs:97->title:54 � NoSuchMessage No message found under code 'problemDetail.title.org.springframework.security.ac
[ERROR]   UndeclaredPathTest.the admin area is closed until admin-access, as problem+json, and no handler runs:97->title:54 � NoSuchMessage No message found under code 'problemDetail.title.org.springframework.security.ac
[ERROR]   UndeclaredPathTest.the admin area is closed until admin-access, as problem+json, and no handler runs:97->title:54 � NoSuchMessage No message found under code 'problemDetail.title.org.springframework.security.ac
[ERROR] Tests run: 41, Failures: 9, Errors: 4, Skipped: 0
[ERROR]   DouckoBackendApplicationIT.only the health endpoint of actuator is reachable:27->only_the_health_endpoint_of_actuator_is_reachable$lambda$1:27->only_the_health_endpoint_of_actuator_is_reachable$lambda$1$0:27 St
[ERROR]   BackupRestoreIT.a broken dump leaves the live data untouched and a safety backup is taken first:97 "usage: node tools/restore.mjs --yes [--project <name>] <backup file>
[ERROR]   BackupRestoreIT.a backup survives losing the volume and restores the data:63->run:36 IllegalState exit 1: node tools/restore.mjs --project doucko-it-3ccc3216 --yes --backup-dir %TEMP%\
[ERROR] Tests run: 5, Failures: 2, Errors: 1, Skipped: 0
```

`BackupRestoreIT` "a broken dump…" failed on argument parsing (`--backup-dir`
did not exist yet), so its red was re-earned by the mutation below.
`FixtureProvenanceTest`'s new cases were not red: the checker lives in the
test itself, so its negative cases (temp repos with an unlisted file and bad
rows) are what show it can fail.

## Green

`npm run verify`: 41 unit tests, 5 integration tests, spotless clean;
`npm run qa:verify`: Overall Pass (trajectory PLANNED, not yet due).
Smoke: `docs/qa/evidence/slice1-compose-smoke.md`.

## Mutation check (restore atomicity)

With `--single-transaction` removed from `tools/restore.mjs`:

```
BackupRestoreIT.a broken dump leaves the live data untouched and a safety backup is taken first:98 expected:<live> but was:<<empty string>>
```

The truncated dump dropped the live table; restored the flag afterwards.

## Fix map

| Finding | Fix | Test |
|---|---|---|
| Bind guard re-parsed `.env` (`export`, duplicates) | `stack.mjs` checks `docker compose config` host_ip of every port | `BindAddressTest` effective-binding cases |
| Leading-zero / mapped / padded addresses accepted | canonical octet regex | `BindAddressTest` refused cases |
| Restore not atomic, no safety copy | `--list` check, safety backup, `--single-transaction` | `BackupRestoreIT` broken dump; `--yes` guard |
| Backups world-readable, overwritable | dir 0700, file `wx` 0600, ms stamp | — (POSIX modes; not observable on Windows) |
| Undeclared writes 403 via CSRF; admin refusals not problem+json | CSRF only under `/api/admin/`; entry point + denied handler write 403 problem+json from the catalog | `UndeclaredPathTest` |
| Actuator `modulith`/`info` open to the LAN | expose `health` only | `DouckoBackendApplicationIT` |
| Logging test never bound a body; levels leaked across contexts | test endpoint binds the body; explicit root level; DEBUG negative control | `RequestContentNotLoggedTest` |
| Provenance roots too narrow; rows outside roots / malformed accepted | roots incl. main resources and non-Kotlin test files; row and root checks | `FixtureProvenanceTest` negative cases |
| ktlint package-name off repo-wide | exemption scoped to `**/adapter/in/**.kt` | spotless check |
| Plain `docker compose up` bypasses the guard | documented (compose.yaml, design.md risks) | — |

# Round 2 (2026-10-04)

Review gate `trace/workflow-runs/20261004-051324-review-gate.json`: 17 confirmed + 5
contested findings on top of round 1. Tests first; RED run with `.env` moved aside so
the pre-fix `stack up --env-file` could not start anything.

## Red (tests only)

```
BackupRestoreIT.restoring an older dump leaves no object from the newer schema behind
BindAddressTest.up refuses --env-file, so the configuration it checks is the one it starts   expected:<2> but was:<1>
DockerContextTest.dockerignore excludes env files, dumps and fixtures at any depth
DouckoBackendApplicationIT.container-level errors are problem+json … (/error, text/html, //, ;, %2e%2e)
DouckoBackendApplicationIT.startup mints and logs no generated password
OperatorToolGuardTest.a link that leads into a git working tree is refused
OperatorToolGuardTest.on Windows a backup directory outside the user profile is refused
OperatorToolGuardTest.restore given a directory prints its usage, not a stack trace          expected:<2> but was:<1>
RequestContentNotLoggedTest.a bound body, query and header values stay out of the logs even at TRACE
RequestContentNotLoggedTest.the capture sees application DEBUG lines and the guard drops …   (after raising web/security to DEBUG in the control)
Unit: Tests run: 48, Failures: 6 · IT: Tests run: 13, Failures: 7
```

The capture control first passed for the wrong reason (application.yaml already kept
those loggers at INFO); it now raises them to DEBUG itself and was RED before the guard.

## Green

`npm run verify`: 48 unit, 13 integration tests; `npm run qa:verify`: Overall Pass
(trajectory PLANNED). The regenerated QA report holds no generated password.

## Mutation checks (also the RED record for task 2.9)

One unit run with all of these applied, then reverted with `git checkout`:

| Mutation | Caught by |
|---|---|
| db publishes `5432:5432` | `ComposeConfigurationTest` — database port is not published |
| backend port without `${DOUCKO_BIND_ADDRESS…}` | `ComposeConfigurationTest` — every published port binds the configurable address |
| `bindAddressProblem` accepts `0.0.0.0` | `BindAddressTest` — refused cases; effective-binding case |
| `/api/admin/**` → `permitAll` | `UndeclaredPathTest` — admin area closed |
| catalog title for 404 removed | `UndeclaredPathTest` — 404 cases |
| `RequestContentLogGuard` removed from `logback-spring.xml` | `RequestContentNotLoggedTest` — TRACE case and capture control |
| `**/.env` removed from `.dockerignore` | `DockerContextTest` |
| unlisted `fixtures/self-authored/probe.json` | `FixtureProvenanceTest` — repository check |

```
Tests run: 48, Failures: 8, Errors: 5
```

Integration mutation, restore without `DROP SCHEMA public CASCADE; CREATE SCHEMA public;`:

```
BackupRestoreIT.restoring an older dump leaves no object from the newer schema behind … exit 1: node tools/restore.mjs …
```

(the replay hit the newer objects and rolled back: caught, and nothing changed).
Earlier: restore without `--single-transaction` → broken-dump test `expected:<live> but was:<empty>`.
Not mutated: `ContractGenerationTest`, `ModularityTest`, `PackageLayoutTest`, the
`stack up --env-file` refusal (a mutant would start the stack).

## Accepted, not changed

- A plain `docker compose up` skips the NFR-2 guard (spec says the guard runs in
  `npm run stack:up`; compose.yaml header and design risks say so).
