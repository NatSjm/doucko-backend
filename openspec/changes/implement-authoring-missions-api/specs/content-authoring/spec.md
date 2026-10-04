## ADDED Requirements

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
