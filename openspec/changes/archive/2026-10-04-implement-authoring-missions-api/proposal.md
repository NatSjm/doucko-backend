# Implement authoring missions (backend)

## Why

Slice 6 of the capability plan (`contract/docs/mvp-capability-plan.md` §4.6).
Store spec: `content-authoring` (`npx openspec show content-authoring --type spec --store doucko`),
requirements "Missions", "Draft and published status", "Mission publishing
rules", "Edit existing content", "Delete only unused content" and the
"Tasks per mission" row of "Field limits". The admin groups published tasks
into missions per topic, publishes and unpublishes them under the FR-57
invariant, edits every entity and deletes only unused content, before child
practice (slice 7) can serve anything. Backend-owned requirements: FR-9,
FR-12, FR-55, FR-56 and FR-57 (derived decisions CA-D4, CA-D6..CA-D10,
CA-D12, CA-D17, CA-D18, CA-D22).

## What Changes

- Implements the generated `AdminMissionsApi` (`listMissions`,
  `createMission`, `getAdminMission`, `updateMission`, `deleteMission`,
  `setMissionStatus`) behind the admin session and CSRF chain of slice 2.
- Extends the slice-5 task operations with the mission rules: `setTaskStatus`
  refuses `DRAFT` for a task in a published mission (FR-57), `updateTask`
  refuses `GRAPHIC` for a task in a published mission (CA-D8) and a topic or
  grade change for a task in any mission (CA-D10), `deleteTask` refuses a task
  in a published mission (CA-D6) or a used task (FR-56, CA-D22) and removes a
  deleted task from its draft missions. `AdminTask.missionIds`,
  `attemptCount` and `deletable` are now computed.
- Module `cz.doucko.content` gains a public API: the `ContentUsage` port
  (attempts, problem reports, opened hints per task; slice 7 implements it, a
  default reports "unused" until then) and the `ServedContent` query (FR-12:
  only `PUBLISHED` `TEXT` tasks inside `PUBLISHED` missions), which slice 7
  consumes.
- Flyway `V3__missions.sql`: `mission` (topic, position unique per topic,
  status) and `mission_task` (ordered task list, a task at most once).

## Impact

- Store spec: content-authoring · Contract pin: docs `4c87d67` (API 0.5.1), no contract change
- Modules: `cz.doucko.content` (extended; new public API types `ContentUsage`, `ServedContent`) · Flyway: `V3__missions.sql`
- Dependencies: slices 1–5; no new Maven dependency
- Frontend: relies on the 400 / 409 shapes listed in design.md "Error handling".
