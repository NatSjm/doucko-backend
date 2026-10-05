# practice-flow Specification

## Purpose
Backend behavior of slice 7 of the practice-flow store spec (with the attempt log of progress-stats): login-free child endpoints served only from published content, the two-attempt state machine with the FR-22 unreadable refusal, hints, reports, save and resume, the append-only attempt log, feedback through the tutor seam, and the practice side of the content usage port.

## Requirements

### Requirement: Child endpoints serve only published content

Every child-facing operation SHALL read content only through the content
module's `ServedContent` query: HQ lists only served missions, and a request
naming a mission that is not served, or a task that is not served or not in
the mission of the URL, SHALL be answered 404 with an
`application/problem+json` body and SHALL store nothing (FR-12, FR-26,
FR-28, FR-30).

#### Scenario: Draft mission and graphic task are invisible

- **GIVEN** a draft mission of published tasks, a published `GRAPHIC` task in a draft mission, a published task in no mission, and a published mission
- **WHEN** the child API is asked for HQ, for each of those missions and tasks, and an attempt, a hint and a report are sent for each task
- **THEN** HQ lists only the published mission, and every other request is 404 problem+json with no attempt, opened hint or report stored

### Requirement: Attempts follow the two-attempt state machine

`submitAttempt` SHALL grade every part with the checker, lock the parts that
were correct on attempt 1, mark the task `CORRECT_FIRST_TRY`,
`CORRECT_SECOND_TRY` or `REVEALED` after the attempt that decides it, and
refuse a further submission with 409 (FR-27, OQ-11). A submission with any
unreadable or missing non-locked part SHALL be answered 200 with `status:
UNPARSEABLE`, listing only those parts, and SHALL store nothing (FR-22). A
malformed request shape SHALL be 400 naming `answers.<partKey>`. Fields other
than `answers` SHALL be ignored (NFR-1).

#### Scenario: Wrong, then correct with a locked part

- **GIVEN** a published two-part task (a = `12`, b = `0.5`) in a published mission
- **WHEN** attempt 1 sends a = `12`, b = `3`, and attempt 2 sends a = `13`, b = `0,5`
- **THEN** attempt 1 is `GRADED` with a `CORRECT`, b `WRONG`, 1 attempt left and no correct value in the body; attempt 2 is `CORRECT_SECOND_TRY` with a `locked` and `CORRECT`; a third submission is 409

#### Scenario: Unreadable part costs nothing

- **GIVEN** a task with 2 attempts left
- **WHEN** a part is `5,,2`, or a 101-code-point string, or missing
- **THEN** the answer is 200 `UNPARSEABLE` with that part's `formatTip`, `attemptNo` null, 2 attempts left, and the attempt log holds no entry

### Requirement: Every attempt, hint and report is stored at once

Each graded attempt SHALL be stored before the response with the raw input
verbatim and the normalized input per part, the verdicts, the hints opened at
that time, the attempt number, a timestamp and whether it is a replay attempt
(FR-31, FR-42). Opened hints SHALL be stored once each, in order, with the
time they were opened (FR-28). A report SHALL store the reason, the trimmed
note and the latest attempt of the task in the current run (FR-30). Nothing
SHALL be edited or deleted afterwards, and editing the task's answer spec
SHALL leave stored verdicts and the task state unchanged.

#### Scenario: Attempt log entry

- **GIVEN** a two-part task with 2 hints opened
- **WHEN** attempt 1 sends a = `12` and b = `1 000` (with U+00A0)
- **THEN** one stored entry holds the raw values verbatim, normalized `12` and `1000`, the verdicts, hints used 2, attempt number 1, a timestamp and replay false

### Requirement: Practice usage blocks deletes

The practice module SHALL implement the content port `ContentUsage`: a task
with any attempt, problem report or opened hint is used, and its attempt
count is the number of stored attempts (FR-56).

#### Scenario: A practised task cannot be deleted

- **GIVEN** a task with one stored attempt in a mission that was then unpublished
- **WHEN** the admin deletes the task and the mission
- **THEN** both answers are 409 and `getAdminTask` reports `attemptCount` 1 and `deletable` false

### Requirement: HQ, progress and continue target

`getHq` SHALL need no credentials and SHALL list the served missions by topic
and position with `taskCount`, `resolvedCount` and `state`, where a task
resolved in any mission counts in every mission that contains it (FR-24,
FR-25, OQ-13, OQ-15). `continueTarget` SHALL point to the first unresolved
task of the in-progress mission practised most recently, and be null when no
mission is in progress (OQ-16).

#### Scenario: States and continue

- **GIVEN** published missions A and B sharing task T, T resolved through A, and one wrong attempt on another task of A
- **WHEN** HQ is read
- **THEN** A and B are both `IN_PROGRESS` with T counted in each, and `continueTarget` names A and its first unresolved task

### Requirement: Feedback comes only from the tutor seam

Attempt feedback SHALL be produced only by `TutorPort`, whose one MVP
implementation is deterministic: `nextHintIndex` names the next unopened hint
or is null, and `revealedAnswers` is set only once the task is `REVEALED`
(FR-44). Replacing the implementation SHALL NOT change how attempts are
submitted.

#### Scenario: Swapped tutor

- **GIVEN** a test tutor that always returns `nextHintIndex` 2
- **WHEN** the same attempt body is submitted
- **THEN** it is accepted unchanged and `feedback.nextHintIndex` is 2
