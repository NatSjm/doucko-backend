# Harden the backend for Phase 5 (G5)

## Why
PLAYBOOK Phase 5 (gate G5) asks for Testcontainers integration across modules, the
module-boundary check, a coverage baseline that holds, and idempotent, self-authored, PII-free
seeds for the frontend's Playwright suites against a real `docker compose` stack. Slice 9
(progress-stats) dropped coverage below the committed ratchet (statements 97.76 → 97.48,
branches 90.94 → 89.95), and the only compose stack is the owner's project `doucko`, which holds
real data and must never be used for tests. Store spec: `platform-foundation` (NFR-2, NFR-3,
NFR-4, NFR-11).

## What Changes
- `tools/e2e-stack.mjs up|down|seed|ps` (npm `e2e-stack:*`): an isolated Compose project
  `doucko-e2e` from `compose.yaml` + `compose.e2e.yaml`, frontend only on `127.0.0.1:18082`,
  throwaway volumes removed by `down -v`, fixed fake test values in `tools/e2e-stack.env`,
  frontend build context from `DOUCKO_FRONTEND_DIR`, and an idempotent seed through the admin
  API (blank generated PDF source, three published tasks of different answer types, one
  published mission).
- Behavioural tests that restore line/branch coverage to at least the committed baseline;
  baseline ratcheted up.
- No endpoint, migration or contract change.

## Impact
- Store spec: platform-foundation · Contract pin: unchanged (docs `0168eba`, API 0.5.1)
- Modules: none in `src/main` changed by the tooling; tests only.
- Dependencies: none new.
