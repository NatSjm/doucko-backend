# content-authoring Specification

## Purpose
Backend behavior of slice 5 of the content-authoring store spec: PDF sources, tasks with answer parts, hints, translations and images, the task list, preview, answer tester and bulk publish under the admin chain, with field and upload limits (NFR-17) and uploaded files stored outside every repository.

## Requirements

### Requirement: Authoring endpoints sit behind the admin chain

Every source, task, image, preview, tester, bulk-publish and reference
operation SHALL be served only under `/api/admin/` and SHALL answer 401
without an admin session and 403 for a data-changing request without the CSRF
token (store spec content-authoring "Authoring requires an admin session").

#### Scenario: No session, no CSRF

- **WHEN** `listSources` is called without a session, and `createTask` is called with a session but no `X-XSRF-TOKEN`
- **THEN** the answers are 401 and 403 problem+json, and nothing is stored

### Requirement: PDF sources are uploaded with validated metadata

The backend SHALL accept a multipart `uploadSource` with title, optional
publisher and edition, grade and subject, store the PDF on the storage volume
and return the source with its page count (FR-1). Title is required (trimmed,
at most 200 code points), publisher at most 200, edition at most 50; grade
and subject SHALL be configured values. A refusal SHALL be 400 naming each
field and SHALL store neither a row nor a file.

#### Scenario: Upload and list a source

- **GIVEN** a signed-in admin
- **WHEN** they upload a generated 120-page PDF with title `Matematika 6 – pracovní sešit`, publisher, edition `1`, grade `6` and subject `math`
- **THEN** the answer is 201 and `listSources` shows that source with page count 120

#### Scenario: Invalid metadata stores nothing

- **WHEN** the title is missing or only spaces, the title has 201 code points, the grade is `7`, `abc`, `6,0` or `99999999999`, or the subject is `physics`
- **THEN** the answer is 400 with an error on that field naming the limit or the allowed values, and no source row and no file exist

### Requirement: Upload limits by content (NFR-17)

A source SHALL be a PDF by content, at most 50 MB; an image SHALL be PNG or
JPG by content, at most 5 MB. Wrong type SHALL be 415, oversize 413, with the
message stating the allowed types and size. A PDF whose page count cannot be
read (encrypted, truncated, broken page tree) SHALL be 400 on `file` with the
message "The PDF could not be read. It may be damaged or password-protected."
Nothing SHALL be stored on any refusal.

#### Scenario: Wrong or oversized files

- **WHEN** a DOCX-like ZIP or a PNG renamed `sesit.pdf` is uploaded as a source, a 51 MB PDF is uploaded, a GIF or a 6 MB JPG is attached to a task
- **THEN** the answers are 415, 415, 413, 415 and 413 problem+json with the allowed types and size, and the storage directory gains no file

#### Scenario: Damaged or protected PDF

- **WHEN** an encrypted PDF, the first half of a PDF, or a PDF with an unreadable page tree is uploaded
- **THEN** the answer is 400 with `errors[0].field = file` and the damaged-PDF message, and no source and no file exist

### Requirement: The PDF is served for the viewer

`getSourceFile` SHALL return the stored PDF bytes unchanged as `application/pdf`, and `getSource` SHALL give the page count that bounds page navigation (FR-2). An unknown source SHALL be 404.

#### Scenario: Open a source in the viewer

- **GIVEN** an uploaded 120-page source
- **WHEN** the admin requests its file and its metadata
- **THEN** the file is byte-identical to the stored PDF with content type `application/pdf`, the page count is 120, and an unknown source id is 404

### Requirement: Uploaded files live outside every repository

The storage root SHALL come from `DOUCKO_STORAGE_DIR` and SHALL NOT lie inside
a git working tree; the backend SHALL refuse to start otherwise.

#### Scenario: Stored files are outside the repositories

- **GIVEN** a source PDF and a task image were uploaded
- **WHEN** their absolute storage paths are compared with the docs, backend and frontend repository roots
- **THEN** both files exist and neither lies inside any of those roots

#### Scenario: Storage root inside a working tree is refused

- **WHEN** the storage root is a directory inside this repository
- **THEN** the storage adapter refuses to start with a message naming the setting

### Requirement: Tasks are created and edited with validated content

`createTask` and `updateTask` SHALL validate every field of `TaskInput`
(FR-3, FR-5..FR-8, CA-D11, CA-D13..CA-D16, CA-D20) and SHALL store nothing on
a refusal; a refusal is 400 with one error per invalid field, using the
contract field paths. A new task SHALL start as `DRAFT`.

#### Scenario: Create a multi-part task

- **WHEN** a task with prompt, topic `Desetinná čísla`, grade 6, difficulty 2, source page 57, task number `4`, sub-part `b`, part `a` INTEGER `12` and part `b` NUMBER_WITH_UNIT `24 cm2` is created
- **THEN** it is stored as `DRAFT`, the parts keep their order, and part `b` reports unit `cm²` and normalized `24 cm²`

#### Scenario: Invalid scalar fields

- **WHEN** difficulty is `4`, `0`, `"abc"`, `2.0` or `99999999999`; page is missing, `121`, `0` or `"2,0"`; grade is `7`; the topic belongs to another grade; prompt has 4001 code points
- **THEN** each save is 400 on `difficulty`, `sourceRef.page` (range `1–120`), `grade`, `topicId` or `prompt`, and no task is stored

#### Scenario: Answer spec rules

- **WHEN** a part has DECIMAL `1.250,75`, `5,,2`, `1000000000000` or `0,1234567`; INTEGER `12,5`; DECIMAL exactly 2 places with `0,5`; FRACTION `3/0` or lowest terms with `2/4`; NUMBER_WITH_UNIT `24` or `cm²` or `24 cm²` with `2400 mm²`; CHOICE_SINGLE with one option, no correct option or options `4` and ` 4 `; TEXT with only spaces or 101 code points; tolerance `-0,1`
- **THEN** each save is 400 with an error on the exact path (e.g. `parts[0].spec.accepted[0]`, `parts[0].spec.options[1].text`, `parts[0].spec.tolerance`)

#### Scenario: Normalized interpretation is returned

- **WHEN** DECIMAL `1 250,75` (no-break space), FRACTION `1 1/2` and NUMBER_WITH_UNIT `24 cm2` are saved
- **THEN** `accepted` keeps the values as entered and `acceptedNormalized` is `1250.75`, `3/2` and `24 cm²`

#### Scenario: Parts, hints, translations

- **WHEN** a TEXT task has no part, duplicate labels, an 11th part, an empty hint, an 11th hint, a translation for locale `de`, or a `uk` prompt of 4001 code points
- **THEN** each save is 400 naming `parts`, `parts[1].label`, `hints[0]`, `hints`, `translations.de` or `translations.uk.prompt`; a GRAPHIC task with zero parts is stored

### Requirement: Task images

`addTaskImage` SHALL append a PNG or JPG (at most 10 per task),
`reorderTaskImages` SHALL take every image id exactly once, and
`deleteTaskImage` SHALL remove the image and its file (FR-4).

#### Scenario: Attach, reorder, limit

- **WHEN** two PNG images are attached, the second is moved first, and an 11th image is attached to a task with 10
- **THEN** the task lists the images in the new order, and the 11th is 400 on `file` naming the limit of 10

### Requirement: Task list filters and search

`listTasks` SHALL filter by grade, topic, source, page, modality and status in
any combination, and SHALL search the Czech prompt case- and
diacritics-insensitively; a blank query applies no text filter and a query
over 200 code points is 400 on `q` (FR-11, CA-D19).

#### Scenario: Combined filters and folded search

- **WHEN** the admin filters by a source, page 57 and status `DRAFT`, and searches `obdelnik` and `OBDÉLNÍK`
- **THEN** exactly the matching tasks are listed, with provenance (source title, edition, page, task number)

### Requirement: Preview and answer tester

`previewTask` SHALL return the child task view (`TaskView`) of any task,
without source reference, with every hint opened and the requested configured
locale as secondary text. `testAnswers` SHALL grade each part with
`AnswerChecker.check` and record nothing (FR-10).

#### Scenario: Tester verdicts

- **WHEN** a DECIMAL part expecting `0.5` is tested with `0,50`, an INTEGER part expecting `12500` with `12 400`, a DECIMAL part with `5,,2`
- **THEN** the results are `CORRECT` `0.5`, `WRONG` `12400`, and `UNPARSEABLE` with the checker's Czech tip, and the database is unchanged

### Requirement: Bulk publish

`bulkPublishTasks` SHALL take 1 to 100 distinct task ids, publish each
independently and report each one; an unknown id is reported with reason
`not found` (OQ-32).

#### Scenario: Mixed selection

- **WHEN** two drafts, one published task and an unknown id are published in bulk
- **THEN** the three tasks are `PUBLISHED` and the unknown id is reported as not published with reason `not found`; an empty list, 101 ids or a repeated id is 400

### Requirement: Source edit and delete rules

`updateSource` SHALL replace only metadata (CA-D3), refuse a `file` field with
400, refuse a grade change while tasks reference the source with 409
(CA-D16), and `deleteSource` SHALL be 409 while tasks reference it (CA-D4)
and otherwise remove the row and the file (CA-D5).

#### Scenario: Referenced source

- **GIVEN** a source referenced by one task
- **WHEN** the admin changes its grade to 7 (with grade 7 configured) or deletes it
- **THEN** both are 409 naming 1 task, and the source and its file are unchanged

### Requirement: Missions are created and edited with validated fields

`createMission` and `updateMission` SHALL take a topic, a position from 1 to
999 unique within that topic, and an ordered list of 0 to 50 task ids, each
an existing task of the mission's topic and each at most once (FR-9, FR-55,
CA-D12, CA-D17, CA-D18). A refusal SHALL be 400 with `errors[].field`
(`topicId`, `position`, `taskIds`, `taskIds[i]`) and SHALL store nothing. A
new mission SHALL be `DRAFT`, and the stored list SHALL keep the submitted
order exactly. The same task MAY be in several missions.

#### Scenario: Create, reorder and share

- **GIVEN** 12 tasks of topic `Desetinná čísla`
- **WHEN** a mission at position 1 is created with 10 of them, then saved with 8 and with 12, its last task is moved first, and one of its tasks is added to a second mission of the same topic
- **THEN** the mission is `DRAFT`, each save answers 200 with exactly the submitted order, and `getAdminTask` of the shared task lists both mission ids

#### Scenario: Field refusals

- **WHEN** a mission is saved with no position, position `0`, `1000`, `"abc"`, `1.5` or a position already used in that topic; with 51 tasks; with an unknown, malformed or repeated task id; or with a task of topic `Úhly`
- **THEN** each answer is 400 on `position`, `taskIds` or `taskIds[i]` (the topic message names `Úhly` and `Desetinná čísla`), and the stored missions are unchanged

### Requirement: Publishing keeps every published mission valid

`setMissionStatus` to `PUBLISHED` SHALL succeed only for a non-empty mission
whose tasks are all `PUBLISHED` and `TEXT`, otherwise 409 with the blocking
task ids in `conflicts` and a `detail` naming them (FR-57, CA-D7). While a
mission is published, `updateMission` SHALL refuse with 409 an empty task
list (CA-D7) and any added task that is not `PUBLISHED` `TEXT` (CA-D9), and
SHALL allow reordering and removing tasks while one remains. A topic change
of a non-empty mission SHALL be 409 with the mission's task ids in
`conflicts` (CA-D10); it is checked before the field rules, so such a
request gets this 409 even when other fields are also invalid. For an empty
task list on a published mission, `conflicts` holds the mission's id.
Unpublishing SHALL always be allowed.

#### Scenario: Publish refusals and success

- **WHEN** an empty mission, a mission with one draft task, and a mission with a published graphic task are published, and then a mission of published text tasks
- **THEN** the first three answers are 409 (the second and third with that task's id in `conflicts`) and stay `DRAFT`, and the last is 200 `PUBLISHED`

#### Scenario: Editing a published mission

- **GIVEN** a published mission M with published text tasks
- **WHEN** a draft task and a published graphic task are added, all tasks are removed, the topic is changed, one task is removed, and the order is reversed
- **THEN** the first three answers are 409 and M is unchanged; the last two are 200 and M stays `PUBLISHED`

### Requirement: Task edits respect the missions that contain the task

`setTaskStatus` to `DRAFT` and an `updateTask` to modality `GRAPHIC` SHALL be
409 while the task is in a published mission, with that mission's id in
`conflicts` (FR-57, CA-D8). An `updateTask` that changes the topic or the
grade SHALL be 409 while the task is in any mission, naming every such
mission (CA-D10). After the mission is unpublished, the task can return to
`DRAFT`.

#### Scenario: Guarded task edits

- **GIVEN** a published text task in draft mission A and published mission B
- **WHEN** it is set to `DRAFT`, changed to `GRAPHIC`, and moved to topic `Úhly`; then B is unpublished and the task is set to `DRAFT`
- **THEN** the first three answers are 409 (the last one with A and B in `conflicts`) and the task is unchanged; the last answer is 200 `DRAFT`

### Requirement: Only unused content is deleted

`deleteTask` SHALL be 409 while the task is in a published mission (CA-D6)
or while any attempt, problem report or opened hint references it (FR-56,
CA-D22); otherwise it SHALL delete the task and remove it from every draft
mission, keeping the order of the remaining tasks. `deleteMission` SHALL be
409 while any of its tasks is referenced that way, and otherwise SHALL
delete the mission and keep its tasks. Usage is read through the module port
`ContentUsage`. `AdminTask.missionIds`, `attemptCount` and `deletable` and
`AdminMission.hasAttempts` and `deletable` SHALL report the same facts.

#### Scenario: Delete guards

- **GIVEN** a usage fake that reports an attempt on task T1, a problem report on T2 and an opened hint on T3
- **WHEN** T1, T2, T3, a task in a published mission, a mission containing T1, an unused draft task in a draft mission, and an unused draft mission are deleted
- **THEN** the first five answers are 409 offering to unpublish (or naming the published mission) and change nothing; the unused task disappears from its mission, and the unused mission disappears while its tasks remain

### Requirement: Only published text tasks in published missions are served

The module query `ServedContent` SHALL list only `PUBLISHED` missions with at
least one task, each with its tasks in order, and SHALL report a task as
served only when it is `PUBLISHED`, `TEXT` and in at least one `PUBLISHED`
mission (FR-12, FR-5). Slice 7 serves the child from this query.

#### Scenario: Served and not served

- **GIVEN** a draft mission of published tasks, including a published graphic task, a published task in no mission, and a published mission
- **THEN** only the published mission is listed, its tasks are served, and the draft mission's tasks, the graphic task and the task in no mission are not served
