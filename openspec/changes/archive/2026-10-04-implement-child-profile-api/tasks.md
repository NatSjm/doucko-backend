## 1. Contract and schema

- [x] 1.1 Confirm the `contract/` pin (docs `4c87d67`, API 0.5.1) generates `ChildProfileApi` (`getProfileSettings`, `updateProfileSettings`).
- [x] 1.2 Flyway migration `V1__child_profile.sql` (single-row `child_profile`).

## 2. Failing tests first (red)

- [x] 2.1 `DisplayNamePolicyTest` (FR-43): trim; blank/unset/empty refused; 50 code points (incl. non-BMP) accepted, 51 refused; newline, tab, U+FFFD refused; messages name the variable and rule, never the value.
- [x] 2.2 `SettingsValidatorTest` (FR-43): absent vs null, `answerMode: null`, enum case, exact locale match, `""`, oversized, wrong types, every invalid field named, GUIDED only after validation.
- [x] 2.3 `ChildProfileStartupTest` (FR-43, NFR-3): context refuses each invalid name without echoing it and creates no profile; an existing profile skips the variable.
- [x] 2.4 `ProfileSettingsTest` (FR-43, NFR-1): web slice over every settings scenario incl. 413, malformed bodies, no-CSRF child calls, admin `/api/admin/profile*` 404 with a session, empty locale configuration.
- [x] 2.5 `ProfileSettingsIT` and `ChildProfileStartupIT` (FR-43, NFR-3): Postgres; first start, restarts with changed/empty/oversized variable, refusal leaves no row, settings survive a restart, logs never contain the name.
- [x] 2.6 `RequestContentNotLoggedTest` (NFR-3): settings `PATCH` payloads join the log-capture test.
- [x] 2.7 Run the tests and confirm RED on assertions. Evidence: `docs/qa/evidence/slice4-red-run.md`.

## 3. Domain and application (green)

- [x] 3.1 `DisplayNamePolicy`, `SettingsValidator` (domain).
- [x] 3.2 `ChildProfileStore` port, `ProfileSettingsService`, `ChildProfileInitializer`, `TaskLocaleProperties`.

## 4. Adapters (green)

- [x] 4.1 `adapter/out/persistence`: entity, repository, `JpaChildProfileStore`.
- [x] 4.2 `adapter/in/web`: `ProfileSettingsController`, `SettingsBodyAdvice`, `ProfileProblemAdvice`; messages.
- [x] 4.3 `application.yaml` locales; `.env.example` and `compose.yaml` `DOUCKO_CHILD_DISPLAY_NAME`.

## 5. Validation, review, archive

- [x] 5.1 `npm run qa:verify` green.
- [x] 5.2 `npx openspec validate implement-child-profile-api --strict`.
- [x] 5.3 review-gate workflow; disposition the findings in `review-findings.json`; re-run 5.1.
- [x] 5.4 Smoke (NOT RUN, see `docs/qa/evidence/slice4-compose-smoke.md`) through 127.0.0.1:8081 (`GET`, `PATCH uk`, `PATCH GUIDED` 422). SKIPPED 2026-10-04: `docker ps` showed `doucko-frontend-1`, `doucko-backend-1` and `doucko-db-1` already running from the main checkout. `npm run stack:up` here uses the same Compose project name `doucko` and port 8081, so it would replace that stack and its data volume users. Covered instead by `ProfileSettingsIT` and `ChildProfileStartupIT` (whole application over real HTTP against Postgres 16, including in-process restarts). Run the smoke when the stack is free.
- [x] 5.5 Update `docs/current-state.md`.
- [x] 5.6 Archive; commit with `Slice: implement-child-profile-api` + `Refs: FR-43, NFR-3`.
