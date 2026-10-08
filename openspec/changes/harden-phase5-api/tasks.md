## 1. E2E stack
- [x] 1.1 `compose.e2e.yaml` override (loopback 18082, configurable frontend context, no restart).
- [x] 1.2 `tools/e2e-stack.mjs` + `tools/lib/e2e-stack.mjs` (guard, env, args) + npm scripts.
- [x] 1.3 Idempotent PII-free seed `tools/lib/e2e-seed.mjs`.
- [x] 1.4 `E2eStackToolTest` (`// @trace NFR-2`, `NFR-3`).
- [ ] 1.5 Proof: up, seed twice, curl through 127.0.0.1:18082, down; `doucko` untouched.

## 2. Coverage
- [x] 2.1 Read the JaCoCo report; add behavioural tests for uncovered slice-9 branches.
- [ ] 2.2 `node scripts/check-coverage-ratchet.mjs --update`.

## 3. Existing Phase 5 items
- [ ] 3.1 Testcontainers ITs across modules and `ModularityTest` pass in `mvnw verify`.

## 4. Validation, review, archive
- [ ] 4.1 `npm run qa:verify` green.
- [x] 4.2 `npx openspec validate harden-phase5-api --strict`.
- [ ] 4.3 review-gate; findings dispositioned in `review-findings.json`.
- [ ] 4.4 Update `docs/current-state.md`.
- [ ] 4.5 Archive.
