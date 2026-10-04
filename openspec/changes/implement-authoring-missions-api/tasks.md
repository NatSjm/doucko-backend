## 1. Contract and schema

- [x] 1.1 Confirm the `contract/` pin (docs `4c87d67`, API 0.5.1) generates `AdminMissionsApi` (`listMissions`, `createMission`, `getAdminMission`, `updateMission`, `deleteMission`, `setMissionStatus`) and `AdminTask.missionIds` / `attemptCount` / `deletable`.
- [x] 1.2 Flyway migration `V3__missions.sql` (`mission`, `mission_task`, unique topic position, a task once per mission).

## 2. Failing tests first (red)

- [x] 2.1 Unit (`*Test.kt`): `MissionRulesTest` (field rules, publish blockers) and the web slice `MissionApiTest` over in-memory stores with a fake `ContentUsage` (FR-9, FR-12, FR-55, FR-56, FR-57).
- [x] 2.2 Integration (`*IT.kt`, Testcontainers Postgres): `MissionsIT` (stored order, shared task, task delete leaves draft missions renumbered, mission delete keeps tasks, 50-task limit, unique position, usage guard with a fake bean, `ServedContent`) (FR-9, FR-12, FR-55, FR-56, FR-57).
- [x] 2.3 NFR-3 log capture covers the mission bodies (no free-text fields; ids and positions only).
- [x] 2.4 Run and confirm RED on assertions. Evidence: `docs/qa/evidence/slice6-red-run.md`.

## 3. Domain and application (green)

- [x] 3.1 `domain/`: mission model, `MissionRules` (field rules, blockers), limits.
- [x] 3.2 `application/`: ports `MissionStore`, `ContentLock`; `MissionService`, `ServedContentService`; mission guards and usage facts in `TaskService`; public API `ContentUsage`, `ServedContent`.

## 4. Adapters (green)

- [x] 4.1 `adapter/out/persistence`: `JdbcMissionStore`, `JdbcContentLock`.
- [x] 4.2 `adapter/in/web`: `MissionController`, mission reader, mapping, body and problem advices; messages.

## 5. Validation, review, archive

- [x] 5.1 `npm run qa:verify` green (604 unit, 52 integration); coverage ratchet updated to lines 99.13, statements 97, functions 97.11, branches 89.17. Evidence: `docs/qa/automated-verification-latest.md`.
- [x] 5.2 `npx openspec validate implement-authoring-missions-api --strict`.
- [ ] 5.3 review-gate workflow; every finding dispositioned in `review-findings.json`; 5.1 re-run after fixes.
- [ ] 5.4 Real-stack smoke (by the owner after the merge, with the frontend half): `npm run stack:up`; sign in through 127.0.0.1:8081; create two published text tasks of `Desetinná čísla`, create a mission at position 1 with both (201 `DRAFT`), publish it (200), set one task to `DRAFT` (409 naming the mission), delete that task (409), unpublish the mission (200), delete the mission (204), confirm both tasks still exist; `npm run stack:down`.
- [ ] 5.5 Update `docs/current-state.md`.
- [ ] 5.6 Archive; commit with `Slice: implement-authoring-missions-api` + `Refs: FR-9, FR-12, FR-55, FR-56, FR-57`.
