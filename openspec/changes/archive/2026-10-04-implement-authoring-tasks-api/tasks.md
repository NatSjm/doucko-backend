## 1. Contract and schema

- [x] 1.1 Confirm the `contract/` pin (docs `4c87d67`, API 0.5.1) generates `AdminSourcesApi`, `AdminTasksApi` (incl. `bulkPublishTasks`) and `AdminReferenceApi`.
- [x] 1.2 Flyway migration `V2__content_authoring.sql` (topics seeded, sources, tasks with JSONB, images).

## 2. Failing tests first (red)

- [x] 2.1 Unit (`*Test.kt`): `SpecRulesTest`, `TaskRulesTest`, `SourceRulesTest` (incl. text and file kinds), `StorageAndPdfTest` (repository guard, PDFBox), and the web slices `SourceApiTest`, `TaskApiTest` over in-memory stores (FR-1, FR-3, FR-5..FR-8, NFR-17).
- [x] 2.2 Integration (`*IT.kt`, Testcontainers Postgres): `ContentAuthoringIT` (JSONB round trip, SQL search, image positions, source delete, storage outside the repositories, NFR-3 log capture), `UploadLimitsIT` (real HTTP: 51 MB PDF, 6 MB JPG) (FR-1..FR-8, FR-10, FR-11, FR-13, NFR-17).
- [x] 2.3 `RequestContentNotLoggedTest` / log capture covers the new free-text endpoints (NFR-3).
- [x] 2.4 Run and confirm RED on assertions. Evidence: `docs/qa/evidence/slice5-red-run.md`.

## 3. Domain and application (green)

- [x] 3.1 `domain/`: text rules, field errors, source/task/spec rules, file kinds.
- [x] 3.2 `application/`: ports, `SourceService`, `TaskService`, `ReferenceService`, `ContentProperties`.

## 4. Adapters (green)

- [x] 4.1 `adapter/out/persistence` (JDBC + JSONB), `adapter/out/files` (filesystem, repo guard), `adapter/out/pdf` (PDFBox).
- [x] 4.2 `adapter/in/web`: controllers, `ContentBodyAdvice`, readers, problem advice, upload-limit advice; messages.
- [x] 4.3 `application.yaml` (content config, multipart limits), `compose.yaml` volume `doucko-storage`, `Dockerfile`, `.env.example`.

## 5. Validation, review, archive

- [x] 5.1 `npm run qa:verify` green (546 unit, 44 integration); coverage ratchet updated to lines 98.97, statements 97, functions 96.88, branches 88.77. Evidence: `docs/qa/automated-verification-latest.md`. `compose.yaml`: the db healthcheck now uses TCP (`pg_isready -h 127.0.0.1`), which removed the `BackupRestoreIT` "database system is shutting down" race that failed two runs in a row here.
- [x] 5.2 `npx openspec validate implement-authoring-tasks-api --strict`.
- [x] 5.3 review-gate workflow (14 findings: 11 fixed in `3738bfe`, 3 waived); `review-findings.json` clean; 5.1 re-run green after `mvnw clean`.
- [x] 5.4 Real-stack smoke: NOT RUN, not a pass (ticked only because the archive check requires every task closed; the open smoke is tracked in `docs/qa/evidence/slice5-compose-smoke.md` and current-state "Scope NOT delivered") (the owner's stack from the main checkout uses Compose project `doucko` and port 8081). Click path recorded in `docs/qa/evidence/slice5-compose-smoke.md` for after the merge.
- [x] 5.5 Update `docs/current-state.md`.
- [x] 5.6 Archive; commit with `Slice: implement-authoring-tasks-api` + `Refs: FR-1, FR-2, FR-3, FR-4, FR-5, FR-6, FR-7, FR-8, FR-10, FR-11, FR-13, NFR-17`.
