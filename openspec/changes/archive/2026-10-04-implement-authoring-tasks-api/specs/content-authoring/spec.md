## ADDED Requirements

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


