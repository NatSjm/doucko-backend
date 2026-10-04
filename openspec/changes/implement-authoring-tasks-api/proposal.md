# Implement authoring tasks (backend)

## Why

Slice 5 of the capability plan (`contract/docs/mvp-capability-plan.md` §4.5).
Store spec: `content-authoring`. The admin needs workbook PDF sources and
tasks typed from them (answer parts with validated answer specs, hints,
translations, images), a task list with filters and search, a preview and an
answer tester, and a bulk publish, before missions (slice 6) and practice
(slice 7) can exist. Backend-owned requirements: FR-1, FR-2, FR-3, FR-4,
FR-5, FR-6, FR-7, FR-8, FR-10, FR-11, FR-13 and NFR-17.

## What Changes

- Implements the generated `AdminSourcesApi` (`listSources`, `uploadSource`,
  `getSource`, `updateSource`, `deleteSource`, `getSourceFile`),
  `AdminTasksApi` (`listTasks`, `createTask`, `getAdminTask`, `updateTask`,
  `deleteTask`, `setTaskStatus`, `addTaskImage`, `reorderTaskImages`,
  `getAdminImage`, `deleteTaskImage`, `previewTask`, `testAnswers`,
  `bulkPublishTasks`) and `AdminReferenceApi` (`getReferenceData`), all behind
  the admin session and CSRF chain of slice 2.
- New module `cz.doucko.content` (domain / application / adapter-in-web /
  adapter-out-persistence / adapter-out-files / adapter-out-pdf): field
  limits, answer-spec authoring rules (through `AnswerChecker.check` of
  `cz.doucko.checking`), file-type sniffing, the `FileStoragePort` with a
  filesystem adapter, a PDF page-count reader (PDFBox).
- Flyway `V2__content_authoring.sql`: `topic` (seeded grade-6 topics),
  `source`, `task` (JSONB parts, hints, translations), `task_image`.
- Configuration: `doucko.content.grades` (6), `doucko.content.subjects`
  (`math`), task locales from `doucko.profile.task-locales`, storage root
  `DOUCKO_STORAGE_DIR` (default `~/.doucko/storage`, refused when inside a git
  working tree), multipart limits (50 MB file). Compose: named volume
  `doucko-storage` mounted at `/var/lib/doucko/storage`.

## Impact

- Store spec: content-authoring · Contract pin: docs `4c87d67` (API 0.5.1), no contract change
- Modules: `cz.doucko.content` (new) · Flyway: `V2__content_authoring.sql`
- Dependencies: slices 1–4; new Maven dependency `org.apache.pdfbox:pdfbox` 3.0.5
- Operators: uploaded files live on the `doucko-storage` volume; `npm run
  backup` still dumps only the database (see design.md, Non-goals).
