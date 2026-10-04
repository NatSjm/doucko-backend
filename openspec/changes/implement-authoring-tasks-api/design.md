# Design — implement-authoring-tasks-api

## Goals / Non-goals

Goals: the content-authoring behaviors of slice 5 that live in the backend
(FR-1..FR-8, FR-10, FR-11, FR-13, NFR-17), behind the admin chain of slice 2.

### Store-spec split (content-authoring)

| Store-spec requirement | Slice | Here |
|---|---|---|
| Authoring requires an admin session | 5 | every handler sits under `/api/admin/` (chain of slice 2); tested with 401 / 403 |
| Field limits (all rows except "Tasks per mission") | 5 | yes |
| Field limits: tasks per mission; "Too many tasks in a mission" | 6 | no |
| Upload a PDF source (FR-1) | 5 | yes |
| Upload limits and storage (NFR-17) | 5 | yes |
| PDF viewer beside the editor (FR-2) | 5 | backend part: `getSourceFile` serves the bytes, `pageCount` gives the range; page navigation is frontend |
| Create a task with a source reference (FR-3) | 5 | yes |
| Fast keyboard-first task entry (NFR-8) | 5 | frontend only (sticky fields, recording) |
| Task images (FR-4) | 5 | yes |
| Answer modality (FR-5) | 5 | yes |
| Graphic tasks are never served in MVP | 7 | child serving does not exist yet; the admin side (stored, filterable) is here |
| Answer parts (FR-6), Answer spec authoring (FR-6, CA-D20) | 5 | yes |
| Hints (FR-7) | 5 | yes |
| Translations (FR-8) | 5 | yes (backend: storage, validation, configured locales, preview) |
| Externalized admin strings | 5 | frontend; backend messages come from `messages.properties` |
| Missions (FR-9) | 6 | no |
| Preview and answer tester (FR-10) | 5 | yes; "Preview matches the child task screen" is a frontend e2e |
| Task list filters and search (FR-11) | 5 | yes |
| Draft and published status (FR-12) | 6/7 | task status exists here; child serving is slice 7 |
| Bulk publish from the task list (OQ-32) | 5 | yes |
| Mission publishing rules (FR-57) | 6 | no |
| Task provenance (FR-13) | 5 | yes (admin side; "never reaches the child" is slice 7) |
| Edit existing content (FR-55) | 6 | the contract tags put `updateSource`, `updateTask`, `deleteSource`, `deleteTask`, `setTaskStatus` in the same generated interfaces, so they are implemented here with the slice-5 rules (validation, CA-D3 file never replaced, CA-D16 source grade lock, CA-D4/CA-D5 source delete). The mission-dependent rules (CA-D6..CA-D10) arrive with missions in slice 6 |
| Editing an answer spec after attempts | 7 | attempts do not exist yet |
| Delete only unused content (FR-56) | 6 | source-delete rules CA-D4, CA-D5 here; attempt / report / hint / mission guards (`ContentUsage` port) in slice 6 |
| Topics come from configuration | 5 | yes (seeded in V2, read-only) |
| No workbook content in repositories | 5 | fixtures generated in tests; provenance manifest |

Non-goals (not delivered in this change):

- Missions, mission publishing rules, attempt/report/hint delete guards
  (slice 6). `AdminTask.attemptCount` is 0, `missionIds` is empty and
  `deletable` is true until those exist.
- Child serving of tasks and images (slice 7).
- **Backup of uploaded files.** `npm run backup` (NFR-10) dumps only Postgres.
  The PDFs and images live on the `doucko-storage` volume; they are not in the
  dump. NFR-10 names only Postgres data, so this is listed as not delivered,
  not as a defect. The real-stack smoke records the volume.
- The frontend nginx default `client_max_body_size` (1 MB) must be raised to
  at least 51 MB by the frontend change for uploads through port 8081.

## Key decisions

1. **Own JSON body reading (`ContentBodyAdvice`).** The generated models cannot
   express the contract conventions: `AttemptRequestAnswersValue` is a sealed
   interface Jackson cannot build, integers must not be coerced, text limits
   are code points after trimming, and errors need dotted field paths with
   indexes. A `RequestBodyAdvice` limited to the content controllers' body
   types reads the raw body (16 KB for the answer tester, 1 MB otherwise; over
   → 413), parses it into a JSON tree kept as a request attribute, and hands
   Jackson a fixed valid placeholder, so the generated DTO never holds a
   submitted value (NFR-3, the slice-4 lesson). Readers in the web adapter turn
   the tree into domain drafts; type errors get the field path.
2. **Answer-spec validation through the checker.** Expected values are parsed
   with the checker's own grammar (CA-D20) by checking each value against a
   spec that accepts only itself (`AnswerChecker.check`, public API). The
   normalized interpretation the checker returns is the editor's
   `acceptedNormalized`. Bounds (|x| < 10^12, ≤ 6 decimals), INTEGER-with-a-
   fraction, exact decimals, lowest terms and the common unit are derived from
   those results. No checker internals are used (Modulith `verify()`).
3. **File type by content (CA-D1).** `%PDF-` for sources; PNG and JPEG
   signatures for images. PDFs are read with PDFBox: encrypted, no `%%EOF`
   near the end (truncated), unparseable or zero pages → 400 on `file`
   (CA-D2). Size limits: Spring multipart `max-file-size` 50 MB (over →
   `MaxUploadSizeExceededException` → 413 from a global advice, message by
   path), image 5 MB checked by the use case (413). Tomcat
   `max-swallow-size: -1` so the client receives the 413 instead of a reset.
4. **Storage outside every repository (NFR-17, NFR-4).** `FileStoragePort`
   with a filesystem adapter rooted at `doucko.content.storage-dir`
   (`DOUCKO_STORAGE_DIR`, default `${user.home}/.doucko/storage`). The adapter
   refuses to start when the root or any ancestor holds `.git` (a working
   tree). Files are written to a temporary name and moved into place, and a
   failed database write deletes the file again, so a refusal leaves nothing.
5. **JDBC, not JPA, for content.** Tasks are a row of filterable columns plus
   JSONB payloads (`parts`, `hints`, `translations`) mapped to typed Kotlin
   models with Jackson. `JdbcClient` keeps the JSONB mapping explicit;
   Hibernate `ddl-auto: validate` only covers entities, so it is unaffected.
6. **Search (CA-D19).** `prompt_folded` holds the prompt case- and
   diacritics-folded (NFD, combining marks removed, lower case) and is matched
   with `strpos`, so `%` and `_` in a query are literal.
7. **Configuration.** Grades and subjects in `doucko.content` (MVP `6`,
   `math`); task locales reuse `doucko.profile.task-locales` through a
   placeholder, so one setting drives both modules.

## Module + hexagon placement

- `domain/`: `Text` (code points, trim, fold), `FieldError`, `SourceRules`,
  `TaskRules`, `SpecRules`, `FileKind`, task and source models. Pure Kotlin;
  depends only on `cz.doucko.checking` (public API).
- `application/`: `SourceService`, `TaskService` (CRUD, images, preview,
  tester, bulk publish), `ReferenceService`, ports `SourceStore`, `TaskStore`,
  `TopicStore`, `FileStoragePort`, `PdfInspector`; `ContentProperties`;
  refusals (`ContentRefusal`).
- `adapter/in/web`: `SourceController`, `TaskController`,
  `ReferenceController`, `ContentBodyAdvice`, `TaskInputReader`,
  `ContentProblemAdvice`, `UploadLimitAdvice`.
- `adapter/out/persistence`: `JdbcSourceStore`, `JdbcTaskStore`,
  `JdbcTopicStore`. `adapter/out/storage`: `FilesystemFileStorage`.
  `adapter/out/pdf`: `PdfBoxInspector`.

## Data model (`V2__content_authoring.sql`)

- `topic(id uuid pk, code text unique, grade int, name text, position int)`;
  seeded grade-6 topics (self-authored, RVP ZV thematic areas).
- `source(id uuid pk, title, publisher, edition, grade, subject, page_count,
  storage_key, created_at)`.
- `task(id uuid pk, prompt, prompt_folded, topic_id fk, grade, difficulty,
  modality, status, source_id fk restrict, page, task_no, sub_part, parts
  jsonb, hints jsonb, translations jsonb, created_at, updated_at)` with
  indexes on `source_id, page` and `topic_id`.
- `task_image(id uuid pk, task_id fk cascade, position, media_type,
  storage_key)`, unique `(task_id, position)` deferrable.

## Error handling

Every refusal is `application/problem+json` with a catalog title and detail
(NFR-9). 400 carries `errors[]` with contract field paths and catalog
messages naming the limit or the allowed values; 404 for unknown ids, 409 for
state rules (`conflicts` lists blocking ids), 413 for size, 415 for type.
Path ids that are not UUIDs, query values of the wrong type, missing
multipart parts are 400 naming the parameter. Submitted values are never
quoted in messages or logs.

## Risks & mitigations

- PDFBox is lenient and may repair a damaged file: the `%%EOF` tail check and
  the zero-page check refuse truncated files; tests generate encrypted,
  truncated and broken-page-tree PDFs.
- Large uploads in memory: at most 50 MB per request, one admin; acceptable on
  the laptop.
- `BackupRestoreIT` failed twice in a row on this machine with "the database system is shutting
  down": `pg_isready` over the Unix socket reported healthy while the image's first-start
  initialisation server was still up. The db healthcheck now checks over TCP (`-h 127.0.0.1`), which
  the initialisation server does not listen on. This also affects the owner's stack (a slightly later
  "healthy" on a first start only).
