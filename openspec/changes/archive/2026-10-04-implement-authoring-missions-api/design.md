# Design — implement-authoring-missions-api

## Goals / Non-goals

Goals: the content-authoring behaviors of slice 6 that live in the backend
(FR-9, FR-12, FR-55, FR-56, FR-57), on top of the slice-5 module
`cz.doucko.content`.

### Store-spec split (content-authoring)

| Store-spec requirement | Slice | Here |
|---|---|---|
| Field limits: tasks per mission; "Too many tasks in a mission" | 6 | yes (400 on `taskIds`) |
| Graphic tasks are never served in MVP: "Graphic task inside a draft mission" | 6/7 | a `GRAPHIC` task can be in a draft mission (admin side); `ServedContent` never returns it. The child HTTP 404 and HQ are slice 7 |
| Missions (FR-9, CA-D12, CA-D17, CA-D18) | 6 | yes. The `<n>/10 tasks` counter is frontend; the backend accepts 0–50 |
| Draft and published status (FR-12) | 6 | the `ServedContent` query (public API of the module). Child endpoints are slice 7 |
| Mission publishing rules (FR-57, CA-D6..CA-D10) | 6 | yes, every scenario. "the child sees it on HQ" is slice 7; here `ServedContent` lists it |
| Edit existing content (FR-55) | 5/6 | sources and tasks were slice 5; missions (topic, position, list, order) here |
| Delete only unused content (FR-56, CA-D4, CA-D6, CA-D22) | 6 | yes, through the `ContentUsage` port (see decision 2). CA-D4/CA-D5 stay as in slice 5 |
| Editing an answer spec after attempts | 7 | attempts do not exist yet |

Non-goals (not delivered in this change):

- Child serving (HQ, mission briefing, task by id, 404 for draft or graphic
  content): slice 7 consumes `ServedContent`.
- The real `ContentUsage` adapter (attempts, problem reports, opened hints):
  slice 7. Until then the default reports every task as unused, so
  `attemptCount` is 0, `hasAttempts` false and the usage guard never fires in
  the running app. The guard itself is tested here with a fake.
- The real-stack smoke (done by the owner after the merge, as for slice 5).

### Carry-over to slice 7 (review F03)

The capability plan assigns FR-12 to this slice only, but its child-facing
store-spec scenarios ("Draft mission is not served", "Published task outside
any published mission", "Published graphic task requested by id", "Graphic
task inside a draft mission", and "the child sees it on HQ" in "Publish a
valid mission") need child endpoints that do not exist before slice 7. This
slice delivers and tests the backend rule as the `ServedContent` query. The
slice 7 change must serve HQ, mission briefing and task-by-id only through
`ServedContent` and must add the HTTP 404 / HQ tests for those scenarios
(`// @trace FR-12`). Until then FR-12 is not claimed as delivered end to end
(`docs/current-state.md`, "Scope NOT delivered"). The plan row itself is a
docs-store change for the owner.

## Key decisions

1. **One advisory lock for structural content writes.** Every write whose
   rule reads another entity (mission create/update/status/delete, task
   update/status/delete, bulk publish) first takes
   `pg_advisory_xact_lock` on one fixed key (`ContentLock` port). One admin,
   low volume: serializing these writes costs nothing and removes every
   check-then-act race between "publish mission" and "unpublish task" or
   "change task topic" and "add task to mission", without lock-ordering
   deadlocks. Rows are read after the lock. Trade-off: two admin tabs saving
   at once wait for each other (milliseconds).
2. **`ContentUsage` port in the module's public API.** `cz.doucko.content.ContentUsage`
   answers, per task, the number of attempts and whether any attempt,
   problem report or opened hint references it (FR-56, CA-D22). Slice 7
   (practice) implements it; content never depends on practice (Modulith).
   Services resolve it through `ObjectProvider<ContentUsage>` and fall back to
   `ContentUsage.NONE` ("unused") when no bean exists, so slice 7 only adds
   its bean and nothing here changes. Tests register a fake bean.
3. **`ServedContent` query in the public API (FR-12).** Published, non-empty
   missions with their tasks in order, and "is this task served": `PUBLISHED`
   `TEXT` and in at least one `PUBLISHED` mission. The publish invariant makes
   the filters redundant, but the query re-checks status and modality, so a
   broken invariant can never serve a draft or graphic task.
4. **Status codes follow the contract split.** Field rules are 400 with
   `errors[].field` (`position`, `topicId`, `taskIds`, `taskIds[i]`); state
   rules are 409 with `detail` and `conflicts`. A topic change of a non-empty
   mission is checked before the field rules (review F04), so the admin gets the
   CA-D10 message rather than one "other topic" error per task, even when the
   same request has other field errors.
5. **Rules are pure domain functions.** `MissionRules.validate` (field rules,
   given the topic, the positions taken in that topic and the referenced
   tasks) and `MissionRules.blockers` (tasks that may not be in a published
   mission) live in `domain/`; the services only load data and apply them.
6. **Messages name things the admin recognises.** A mission is named by its
   topic and position ("Desetinná čísla, mission 1"); a task by its task
   number and page ("task 4b, page 57"). Both come from stored content and
   seeded topics, never from the request being refused (NFR-3).

## Module + hexagon placement

- `cz.doucko.content` (public API): `ContentUsage`, `ServedContent`, `ServedMission`.
- `domain/`: `Mission`, `MissionContent`, `MissionDraft`, `MissionContext`,
  `MissionRules`, `Limits.MISSION_TASKS` / `POSITION_MAX`.
- `application/`: `MissionService`, `ServedContentService`, ports
  `MissionStore`, `ContentLock`; `TaskService` gains the mission guards and
  the usage facts (`TaskFacts`).
- `adapter/in/web`: `MissionController` (`AdminMissionsApi`),
  `JsonReaders.mission`, `ApiMapping.adminMission`; the body and problem
  advices cover the new controller.
- `adapter/out/persistence`: `JdbcMissionStore`, `JdbcContentLock`.

## Data model (`V3__missions.sql`)

- `mission(id uuid pk, topic_id fk topic, position int 1..999, status, created_at, updated_at)`,
  unique `(topic_id, position)` (CA-D17).
- `mission_task(mission_id fk mission on delete cascade, task_id fk task on delete restrict, position int 1..50)`,
  primary key `(mission_id, task_id)` (CA-D12), unique `(mission_id, position)`
  deferrable initially deferred (lists are rewritten in one transaction),
  index on `task_id`.
- A mission's task list is rewritten as a whole on save (delete + insert in
  order). Deleting a task first removes its `mission_task` rows and renumbers
  the remaining positions; the restrict foreign key turns any missed path into
  a 409, never a silent orphan.

## Error handling

| Case | Status | Shape |
|---|---|---|
| unknown mission id | 404 | problem |
| `missionId` not a UUID | 400 | `errors[0].field = missionId` |
| `topicId` missing, not a UUID, unknown | 400 | `topicId` |
| `position` missing, not an integer, outside 1–999, taken in the topic | 400 | `position` |
| `taskIds` missing / over 50 | 400 | `taskIds` |
| `taskIds[i]` not a UUID, unknown, repeated, of another topic (message names both topics) | 400 | `taskIds[i]` |
| topic change of a non-empty mission (CA-D10), checked before the field rules | 409 | `conflicts` = the mission's task ids |
| publish: empty mission (CA-D7) | 409 | `conflicts` = [] |
| publish: draft and/or graphic tasks (FR-57) | 409 | `conflicts` = those task ids, detail names them |
| update of a published mission: added task not `PUBLISHED` `TEXT` (CA-D9) | 409 | `conflicts` = those task ids |
| update of a published mission to an empty list (CA-D7) | 409 | `conflicts` = the mission |
| task → `DRAFT`, task → `GRAPHIC` while in a published mission (FR-57, CA-D8) | 409 | `conflicts` = the published missions |
| task topic or grade change while in any mission (CA-D10) | 409 | `conflicts` = the missions |
| delete task in a published mission (CA-D6) | 409 | `conflicts` = the published missions |
| delete used task / mission with a used task (FR-56, CA-D22) | 409 | `conflicts` = the used task ids; detail offers to unpublish |
| concurrent database rule violation | 409 | problem (slice-5 handler) |

Every 409 uses the slice-5 problem title "Not allowed now"; the `detail`
comes from `content.conflict.<code>` in `messages.properties`.

## Risks & mitigations

- The default `ContentUsage` makes every task deletable until slice 7: no
  attempt exists before slice 7, so the answer is true today. Slice 7 must
  register its adapter; its tests cover the wiring.
- Whole-list rewrite of `mission_task`: at most 50 rows, one admin.
