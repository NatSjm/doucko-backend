# Design — harden-phase5-api

## Goals / Non-goals
- Goal: a real-stack target for the frontend Playwright suite that cannot touch project `doucko`.
- Goal: coverage back at or above the ratchet with behavioural tests only.
- Non-goal: CI integration of the e2e stack; changing product behaviour.

## Key decisions
1. **Override file, not a second compose file.** `compose.e2e.yaml` only changes what differs
   (frontend build context, `ports: !override` to `127.0.0.1:18082:80`, `restart: "no"`), so the
   e2e stack runs the same images and healthchecks as production.
2. **Project name is a constant with a guard.** Every docker call is built by
   `e2eComposeArgs()`, which passes `-p doucko-e2e` through `assertE2eProject`; there is no flag to
   choose a project, and `COMPOSE_PROJECT_NAME` / `COMPOSE_FILE` and the secret variables are
   removed from the child environment, because compose lets the process environment win over
   `--env-file`. `down -v` therefore only removes `doucko-e2e_*` volumes.
3. **Committed fake values** (`tools/e2e-stack.env`) instead of `.env`: the stack is loopback-only
   and throwaway; the values say `not-secret`. The child name is a placeholder (NFR-3).
4. **Seed through the public API**, matched by source title, exact task prompt and, for the
   mission, topic + position — missions have no name in the contract (CA-D17), so "E2E Seed
   Mission" is the mission at topic Přirozená čísla, position 1. Creates/publishes only what is
   missing; it never resets practice data (a fresh stack does).
5. **NFR-2** is checked on the rendered config (`docker compose config`) before `up`, as
   `stack.mjs` does; only the frontend may publish a port.

## Module + hexagon placement
Operator tooling under `tools/` (Node, no dependencies); pure parts in `tools/lib/e2e-stack.mjs`
and `tools/lib/e2e-seed.mjs`, tested by `E2eStackToolTest` through `--dry-run`.

## Risks & mitigations
- Two agents starting the stack at once → the shared `scratch/qa-lock` mkdir lock covers `up`..`down`.
- Port 18082 taken → `up` fails loudly; nothing else is affected.
