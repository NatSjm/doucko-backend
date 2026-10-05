# Design — implement-practice-flow-api

## Goals / Non-goals

Goals: the backend side of the practice flow (FR-22, FR-24..FR-31, FR-42,
FR-44, NFR-5 budget share) in a new module `cz.doucko.practice`, served only
through the slice-6 `ServedContent` query, plus the slice-6 carry-overs
(FR-12 child-facing scenarios, the real `ContentUsage` adapter) and the
admin-access scenario "Child write is limited to its own practice" (NFR-1).

### Store-spec split

| Store-spec requirement | Here |
|---|---|
| practice-flow: HQ without login (FR-24) | yes: `getHq` login-free, 200 with the profile's HQ data |
| practice-flow: Child side cannot change content (NFR-1) | yes: attempt bodies ignore every field but `answers`; the admin 401 case is slice 2 and re-tested here |
| practice-flow: Mission list on HQ (FR-25, OQ-13, OQ-15, OQ-16) | yes: grouping, order, x/N, states, shared-task resolution. "Shared task closes the second mission" XP part is slice 8 |
| practice-flow: Start or continue within two clicks (NFR-7) | backend part: `continueTarget` (most recent in-progress mission, first unresolved task). Clicks are frontend |
| practice-flow: Task screen (FR-26) | backend part: `TaskView` (prompt, images, parts, labels, hints, translation). Focus, keyboard and load time are frontend |
| practice-flow: Task-scoped requests are checked against the content (FR-26, FR-28, FR-30) | yes: 404 for every case, nothing stored |
| practice-flow: Two attempts, then reveal (FR-27, OQ-11, OQ-12) | yes: the state machine below; colours and copy are frontend |
| practice-flow: Oversized input is refused without cost | yes: 101 code points → UNPARSEABLE; 21 elements, element > 100, note > 1000 → 400; body > 16 KB → 413; huge integers graded by value |
| practice-flow: Hints on demand (FR-28) | yes, incl. before the first attempt and after the reveal; 409 when none is left |
| practice-flow: Ukrainian translation toggle (FR-29) | backend part: `LocalizedText.secondary` follows the profile setting of slice 4; `translationAvailable` |
| practice-flow: Report a problem (FR-30) | yes: reasons, `OTHER` needs a note, latest raw attempt of the current run attached |
| practice-flow: Save and resume (FR-31) | yes: every attempt and hint stored before the response; `getCurrentTask` and `getTask` re-read the stored state (attempts used, locked parts, `lastFeedback`) |
| practice-flow: Mission complete screen (FR-32), Replay (FR-33) | slice 8. Placeholders only (see decision 7) |
| practice-flow: Deterministic feedback through the tutor seam (FR-44) | yes: `TutorPort`, one deterministic implementation, architecture test, swap test, no AI dependency |
| progress-stats: Attempt log contents (FR-42) | yes: raw verbatim and normalized per part, verdicts, hints used, attempt number, timestamp, replay flag; UNPARSEABLE never logged |
| progress-stats: Attempt log is append-only (FR-42) | yes: insert-only store; an answer-spec edit keeps past verdicts, task state and stored rows. Re-evaluation is slice 9 |
| answer-checking: Unreadable submissions are refused at the submission endpoint (FR-22) | yes |
| content-authoring FR-12 child-facing scenarios (carried from slice 6) | yes: HTTP tests over `ServedContent` (`// @trace FR-12`) |
| content-authoring: Delete only unused content (FR-56) | the real `ContentUsage` adapter; the slice-6 guard fires on real attempts, reports and hints |
| admin-access: Child write is limited to its own practice (NFR-1) | yes |

Non-goals (not delivered in this change):

- XP, task awards, hint costs, completion bonuses, ranks, rank-ups, the
  mission-complete screen and replays as gamification defines them (slice 8).
  Their contract fields are placeholders (decision 7).
- Stats, flag queue, re-evaluation and dismiss (slice 9). Reports are stored
  `OPEN` for the queue.
- Frontend concerns: copy, focus, keyboard, colours, timing in the browser,
  the 2-click rule, the input length cap.
- The real-stack smoke: the owner runs it after the merge
  (`docs/qa/evidence/slice7-compose-smoke.md`).

## Key decisions

1. **Runs and scopes.** Practice state lives per task and *scope*. The
   primary scope (a fixed nil UUID) holds the child's first play-through of
   every mission. A task shared by several missions (OQ-15) therefore has one
   state: attempts and hints under mission A and mission B count against the
   same two attempts, and once resolved anywhere it is resolved in every
   mission that contains it, skipped by `getCurrentTask`, and a further
   attempt through any of those missions is 409. A replay run (`practice_run`
   row) is its own scope with fresh attempts and hints. The *current run* of
   a mission is its latest replay run while that run is not finished and still
   has an unresolved task, otherwise the primary scope. A replay run is marked
   finished (`finished_at`) by the attempt that resolves its last task, so a
   task the admin adds to the mission later belongs to the primary scope
   (review F02).
2. **Task state machine** (per task and scope, derived from the stored
   attempts, never stored itself):

   | Stored attempts | `state` | `attemptsUsed` | `attemptsLeft` |
   |---|---|---|---|
   | none | `UNRESOLVED` | 0 | 2 |
   | #1 every part correct | `CORRECT_FIRST_TRY` | 1 | 0 |
   | #1 not all correct | `UNRESOLVED` | 1 | 1 |
   | #1 not all correct, #2 every part correct | `CORRECT_SECOND_TRY` | 2 | 0 |
   | #1 not all correct, #2 not all correct | `REVEALED` | 2 | 0 |

   Parts correct on attempt 1 are locked: on attempt 2 their submitted value
   (if any) is ignored and not re-graded, and they are reported `CORRECT`,
   `locked: true`, with their attempt-1 normalized value. A part that is not
   locked and has no answer in the body is graded as empty input, i.e.
   `UNPARSEABLE` (the answer tester's rule). Order of checks for
   `submitAttempt`: path ids (400) → body size (413) and one JSON object
   (400) → served mission and task in it (404) → body shape (400) → task already resolved in the current run (409) → any
   unreadable non-locked part (200 `UNPARSEABLE`, nothing stored) → graded and
   stored (200 `GRADED`).
3. **One practice lock.** Every practice write takes
   `pg_advisory_xact_lock` on a fixed practice key, then re-reads the state.
   One child, low volume: two tabs submitting at once are serialized, so a
   task can never get a third attempt or a duplicate hint index.
4. **Feedback only through `TutorPort` (FR-44).** The attempt use case passes
   the task, the scope's opened hints and the new state to `TutorPort`; the
   one implementation, `DeterministicTutor`, returns `nextHintIndex` (the next
   unopened hint, whenever one exists, `null` otherwise) and, only once the
   task is `REVEALED`, `revealedAnswers` (per part the first accepted answer as
   entered; for `CHOICE_*` the accepted option keys). No ids or timestamps.
   An ArchUnit test proves `Feedback` is built only by `TutorPort`
   implementations and that there is exactly one. `PracticeIT` runs with
   outbound network denied (`DenyOutboundNetwork`: a default `ProxySelector`
   that refuses every non-local target the JDK asks it about) and fails on any
   refused connection (TC-6, review F04).
5. **Request bodies are read by a practice body advice.** The generated
   `AttemptRequestAnswersValue` is a sealed interface Jackson cannot build,
   and the contract wants code-point limits and `answers.<key>` paths. Like
   the content endpoints, the raw body (at most 16 KB, else 413) is parsed to
   one JSON tree kept as a request attribute, and Jackson binds a fixed
   placeholder, so no generated DTO ever holds a submitted value (NFR-3).
   `ContentBodyAdvice` is now scoped to the content controllers. Unknown
   top-level fields are ignored (NFR-1, gamification).
6. **Revealed answers keep the generated models.** The generated
   `FeedbackRevealedAnswersInnerAnswer` (oneOf string | array) is a Kotlin
   sealed interface without implementations. The web adapter wraps the value
   in a JDK proxy of that interface, and a Jackson module writes the wrapped
   string or array. Every response is still a generated model (NFR-12).
7. **Slice-8 placeholders, as the contract allows.** `AttemptResult.xpAwarded`
   and `bonusXp` 0, `completedMissionIds` [], `rankUp` null;
   `missionComplete` true when the attempt resolved the last unresolved task of
   the current run. HQ: `totalXp` 0, `rank` the first rank of a fixed
   five-rank placeholder ladder (0, 150, 400, 800, 1500 XP), `nextRank` the
   second, `pendingRankUp` null; `solvedTasks` counts tasks resolved in the
   primary scope. Briefing `xpAvailable` 0. `startReplay`: 409 unless the
   mission is complete in the primary scope; returns the unfinished replay run
   unchanged, else starts a new one. `getMissionSummary`: the latest completed
   run (`replay` flag, `taskCount`, `firstTryCount` = tasks resolved first try
   in that run through this mission), `xpEarned` 0, no rank; 409 when no run
   is complete. `ackRankUp`: 204 (nothing is ever pending), 400 on a bad
   `rankIndex`. Slice 8 replaces these with the gamification rules.
8. **HQ states.** A mission is `COMPLETE` when every served task is resolved
   in the primary scope, `IN_PROGRESS` when not complete and any of its tasks
   has a primary-scope attempt or opened hint (through any mission), else
   `NEW`. `continueTarget` is the in-progress mission whose latest
   primary-scope activity *through that mission* is most recent (missions
   with none come last, then HQ order), with its first unresolved task; null
   when no mission is in progress. Replays never count.
9. **Translation follows the profile.** `LocalizedText.secondary` is set only
   when the profile's `secondaryTaskLocale` is on and the text has a
   translation in it. `translationAvailable` is true when the task has a
   non-empty translation in the profile's locale, or, with the toggle off, in
   any configured task locale (the admin preview's rule).
10. **New public APIs.** `content`: `ServedContent.task(taskId)` (the served
    task: prompt, parts with checker specs, units and options, hints,
    translations, image ids), `ServedContent.image(imageId)` (bytes of an
    image of a served task), `ServedMission.topicName`. `profile`:
    `ChildProfile` (`displayName()`, `secondaryTaskLocale()`), implemented by
    `ProfileSettingsService`. `practice` depends on `content`, `checking` and
    `profile` only through these; `content` still never depends on practice
    (it receives `PracticeContentUsage` as its `ContentUsage` bean).
11. **Logs (NFR-3).** The practice code logs nothing about requests. The log
    guard also caps `org.springframework.jdbc` at INFO, because
    `StatementCreatorUtils` prints bound parameter values (raw answers,
    notes) at TRACE, and all of `org.hibernate.orm`, whose entity dumps (DEBUG) and
    extracted values (TRACE) print the display name. A log-capture test runs a full attempt, hint, report and
    HQ read at TRACE against Postgres and greps for the submitted values and
    the display name.

## Module + hexagon placement

- `cz.doucko.practice` (module marker `PracticeModule`; nothing exposed).
- `domain/`: `Scope`, `TaskState`, `AttemptRecord`, `PartRecord`,
  `HintRecord`, `ReportRecord`, `RunRecord`, `RawAnswer`, `TaskProgress`
  (state machine), `Grading` (locks, unreadable refusal, verdicts),
  `MissionProgress` (x/N, state), `ReportRules`, `PlaceholderLadder`.
- `application/`: ports `PracticeStore`, `TutorPort`; `PracticeService`
  (queries and commands), `PracticeContentUsage` (`ContentUsage`),
  refusals (`PracticeNotFound`, `PracticeConflict`, `InvalidPracticeInput`).
- `adapter/in/web`: `ChildHqController` (`ChildHqApi`),
  `ChildPracticeController` (`ChildPracticeApi`), `PracticeBodyAdvice`,
  `PracticeReaders`, `PracticeMapping`, `PracticeProblemAdvice`,
  `RevealedAnswers` (proxy + Jackson module).
- `adapter/out/persistence`: `JdbcPracticeStore`.
- `adapter/out/tutor`: `DeterministicTutor`.
- `content` (public API): `ServedTask`, `ServedPart`, `ServedOption`,
  `ServedTranslation`, `ServedImage`; `ServedContentService` implements them.
- `profile` (public API): `ChildProfile`.
- `shared`: `RequestContentLogGuard` guards `org.springframework.jdbc`.

## Data model (`V4__practice.sql`)

- `practice_run(id uuid pk, mission_id uuid fk mission on delete cascade, started_at, finished_at null)` — replay runs only; `finished_at` is set once, when the run's last task is resolved.
- `attempt(id uuid pk, task_id fk task on delete restrict, mission_id uuid, scope uuid, replay boolean, attempt_no 1..2, hints_used >= 0, correct boolean, parts jsonb, created_at)`,
  unique `(task_id, scope, attempt_no)`, index `(scope, task_id)`. `parts` is a
  JSON array of `PartRecord` (`key`, `raw` string | array | null for a locked
  part, `normalized`, `verdict`, `locked`). No foreign key on `mission_id` /
  `scope`: the log outlives mission edits and is never rewritten.
- `opened_hint(task_id fk task on delete restrict, scope uuid, hint_index >= 1, mission_id uuid, opened_at)`, primary key `(task_id, scope, hint_index)`.
- `problem_report(id uuid pk, task_id fk task on delete restrict, mission_id uuid, reason, note, attempt_id fk attempt, status 'OPEN', created_at)`.
- The `task` foreign keys make the FR-56 guard a database rule too: a used
  task can never be deleted, even if a path skipped `ContentUsage`.

## Error handling

All child refusals are `application/problem+json` with title and detail from
`messages.properties` (NFR-9), never quoting a submitted value (NFR-3).
Order: a non-UUID path id (400, contract Conventions) and a body over 16 KB
(413) or not one JSON object (400) are refused before any lookup; then the
404 rules; then field rules (400) and state rules (409).

| Case | Status | Shape |
|---|---|---|
| `missionId`, `taskId` or `imageId` not a UUID | 400 | `errors[0].field` = that parameter |
| mission unknown, draft or without served tasks | 404 | problem (`practice-not-found`) |
| task unknown, draft, `GRAPHIC`, or not in the mission of the URL | 404 | problem; nothing stored |
| image not of a served task, or its file is missing | 404 | problem |
| a body with invalid fields for a task that is not served | 404 | problem (the 404 check runs before the field rules, review F05) |
| body empty or not one JSON object | 400 | problem (`practice-unreadable`) |
| body over 16 KB | 413 | problem (`child-body-too-large`) |
| attempt: `answers` missing or not an object | 400 | `answers` |
| attempt: unknown part key | 400 | `answers.<key>` |
| attempt: string for a `CHOICE_*` part, array for another part, any other JSON type | 400 | `answers.<key>` |
| attempt: more than 20 elements, an element over 100 code points | 400 | `answers.<key>` |
| attempt: task resolved in the current run | 409 | problem (`practice-conflict`, detail "already resolved") |
| attempt: any non-locked part unreadable, missing, or over 100 code points | 200 | `status: UNPARSEABLE`, only those parts, nothing stored |
| hint: every hint already open (or the task has none) | 409 | problem (detail "no hint left") |
| report: `reason` missing or not one of the three | 400 | `reason` |
| report: `note` not a string, over 1000 code points after trimming, or blank / missing with `OTHER` | 400 | `note` |
| replay: mission not complete | 409 | problem |
| summary: no completed run | 409 | problem |
| rank-up ack: `rankIndex` missing, not an int32, negative | 400 | `rankIndex` |

## Risks & mitigations

- Placeholders could be mistaken for gamification behavior: they are listed
  here, in `docs/current-state.md` "Scope NOT delivered", and the contract
  allows them.
- Shared-task state across missions is a reading of OQ-15 ("one resolution
  shared by all of them"); attempts made under A count in B. The frontend
  gets that state from `getTask` in every mission.
- `missionComplete` in `TaskView.lastFeedback` is recomputed on read (true
  when the task is resolved and so is every task of the current run).
