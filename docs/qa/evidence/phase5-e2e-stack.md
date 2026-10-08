# Phase 5 (harden-phase5-api) — isolated e2e stack proof and G5 status

- **Date:** 2026-10-08 (Europe/Prague), backend branch `phase5-hardening` at `d3081de` (review fixes included)
- **Result: PASS.** `node tools/e2e-stack.mjs up`, `seed` twice, requests through `127.0.0.1:18082`,
  `down`; the `doucko-e2e` volumes are gone and the owner's project `doucko` (containers and the
  volumes `doucko_doucko-pgdata`, `doucko_doucko-storage`) is identical before and after.
  Only the committed fake test values of `tools/e2e-stack.env` were used.
- Missions have no name in the contract (CA-D17): "E2E Seed Mission" is the mission at topic
  Přirozená čísla, position 1.
- QA battery after the run: `npm run qa:verify` Overall result: Pass (813 unit, 161 integration
  tests incl. `ModularityTest`, all Testcontainers ITs) — `docs/qa/automated-verification-latest.md`.
- Coverage ratchet `--update`: lines 99.27 → 99.30, statements 97.76 → 97.86, functions 97.74,
  branches 90.94 → 91.14 (`quality/coverage-baseline.json`).

## Run log

```text
## before
buildx_buildkit_default Up 2 days
doucko-backend-1 Up 7 hours (healthy)
doucko-db-1 Up 2 days (healthy)
doucko-frontend-1 Up 7 hours
buildx_buildkit_default_state
doucko-storage
doucko_doucko-pgdata
doucko_doucko-storage
order-tracker_grafana
order-tracker_loki
order-tracker_orders
order-tracker_prometheus
order-tracker_tempo
## up
 Container doucko-e2e-db-1 Healthy 
 Container doucko-e2e-backend-1 Healthy 
 Container doucko-e2e-frontend-1 Healthy 
e2e-stack: up at http://127.0.0.1:18082
up exit 0
## e2e volumes
doucko-e2e_doucko-pgdata
doucko-e2e_doucko-storage
## seed 1
e2e-stack: source "E2E Seed Source" created
e2e-stack: task "E2E Seed: Kolik je 6 · 7?" created
e2e-stack: task "E2E Seed: Kolik je 6 · 7?" published
e2e-stack: task "E2E Seed: Kolik je 1,5 + 2,25?" created
e2e-stack: task "E2E Seed: Kolik je 1,5 + 2,25?" published
e2e-stack: task "E2E Seed: Které číslo je sudé?" created
e2e-stack: task "E2E Seed: Které číslo je sudé?" published
e2e-stack: mission "E2E Seed Mission" created
e2e-stack: mission "E2E Seed Mission" published
e2e-stack: seeded {"sourceId":"02d1c01d-c4d6-4d38-9e41-3a2588aaea46","taskIds":["2ae9d7a8-77dd-49a5-8f1f-2962da852211","d6636ff7-ea26-4cdf-84b3-c6015585cac3","b685279a-bb16-4036-af51-a24e5246b08e"],"missionId":"95e0eaa7-2064-4d4c-929a-ef59818f02e9"}
seed1 exit 0
## seed 2
e2e-stack: source "E2E Seed Source" exists
e2e-stack: task "E2E Seed: Kolik je 6 · 7?" exists (PUBLISHED)
e2e-stack: task "E2E Seed: Kolik je 1,5 + 2,25?" exists (PUBLISHED)
e2e-stack: task "E2E Seed: Které číslo je sudé?" exists (PUBLISHED)
e2e-stack: mission "E2E Seed Mission" exists (PUBLISHED)
e2e-stack: seeded {"sourceId":"02d1c01d-c4d6-4d38-9e41-3a2588aaea46","taskIds":["2ae9d7a8-77dd-49a5-8f1f-2962da852211","d6636ff7-ea26-4cdf-84b3-c6015585cac3","b685279a-bb16-4036-af51-a24e5246b08e"],"missionId":"95e0eaa7-2064-4d4c-929a-ef59818f02e9"}
seed2 exit 0
## curl through 127.0.0.1:18082
POST /api/admin/session 204
missions: 1 [{"position":1,"status":"PUBLISHED","tasks":3}]
tasks: 3 ["PUBLISHED E2E Seed: Kolik je 6 · 7?","PUBLISHED E2E Seed: Kolik je 1,5 + 2,25?","PUBLISHED E2E Seed: Které číslo je sudé?"]
sources: 1 ["E2E Seed Source pages=2"]
child hq: Testovací žák [{"name":"Přirozená čísla","missions":["1:3:NEW"]}]
## down
 Volume doucko-e2e_doucko-pgdata Removing 
 Network doucko-e2e_default Removing 
 Volume doucko-e2e_doucko-storage Removing 
 Volume doucko-e2e_doucko-pgdata Removed 
 Volume doucko-e2e_doucko-storage Removed 
 Network doucko-e2e_default Removed 
down exit 0
## e2e volumes left: 0
## after
buildx_buildkit_default Up 2 days
doucko-backend-1 Up 7 hours (healthy)
doucko-db-1 Up 2 days (healthy)
doucko-frontend-1 Up 7 hours
buildx_buildkit_default_state
doucko-storage
doucko_doucko-pgdata
doucko_doucko-storage
order-tracker_grafana
order-tracker_loki
order-tracker_orders
order-tracker_prometheus
order-tracker_tempo
doucko untouched: IDENTICAL
```

## gate:status (after the ratchet update)

```text
  G0  PASS           scaffold + loop installed
  G1  needs sign-off requirements signed off
  G2  PASS           baseline specs  [traceability]
  G3  needs sign-off capability plan + acceptance methods exist  [acceptance-methods --mode=existence]
  G4  PASS           per-slice  [traceability · trajectory · acceptance-methods --mode=artifact · factory-integrity]
  G5  PASS           hardening (coverage)  [coverage-ratchet]
  G6  PASS           QA proof  [acceptance-methods --mode=artifact]
  G7  FAIL           release  [traceability --release --strict-tests --strict-recordings · trajectory --release --check-fresh · acceptance-methods --mode=artifact · factory-integrity]
  G8  PASS           UAT (trace regressions)  [traceability]
```
