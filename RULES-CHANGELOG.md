# RULES-CHANGELOG — the check promotion/deprecation ledger

Every deterministic check in `scripts/` is registered here with its rung on
the promotion ladder. This ledger is the single place where the gate set is
allowed to change strictness. It implements mechanisms 5 and 7 of
[the reflection design](https://github.com/koldovsky/project-factory/blob/0d7286a187df7ca22af80be78c9bd32491f5a781/docs/field-reports/2026-07-02-reflection-mechanism-design.md).

## Ladder rules

1. **Rungs:** `experimental` → `soft` → `hard` → (`deprecated`).
   - `experimental` — runs in the battery, prints its verdict, exit 0 in
     default mode even on NOT-EARNED (never on FAIL of an evaluated guard);
     never prints a PASS it has not earned. CI runs it without `--strict`.
   - `soft` — verdict counted and rendered by gate-status/qa-verify; failures
     warn but do not block merge. (Fail-open emitters live here permanently by
     design — see the ledger row notes.)
   - `hard` — exit code blocks the gate it backs. qa-verify may not print an
     overall Pass while a hard member is FAIL or NOT-EARNED.
2. **Promotion (experimental→soft, soft→hard)** requires, recorded as a new
   row in the history table below: **≥ 2 truthful runs** where the check's
   verdicts were confirmed correct, **0 false positives**, and a link to the
   evidence (run artifacts, field report, or fixture output). No check enters
   `hard` without an **executed red→green proof** (a failing fixture and a
   passing fixture actually run, per mechanism 7 — no decorative checks enter
   the gate set).
3. **Hard-from-day-one is the documented exception**, allowed only when the
   red→green evidence already exists from the field *before* the check ships
   (the check must cite it in its row). Everything else starts `experimental`.
4. **Demotion (`hard` → anything looser) or deprecation of a hard check
   requires an owner-signed entry**: a row in the history table carrying the
   owner's name/handle, the reason, and a link to the waiver artifact under
   `docs/qa/waivers/`. An unsigned demotion is tampering — the integrity lock
   (mechanism 8) treats gate-bearing script drift without a matching
   `Refs: PD-x` commit as a hard red.
5. **Grandfathering:** checks that predate this ledger enter as `hard`
   (grandfathered) because the field study already exercised them red and
   green in real deliveries; their evidence link points at the field reports.
6. Ratchet baselines (`quality/*-baseline.json`) move only via each check's
   `--update` and only in the tightening direction (Zone 2 of the safety
   model). Loosening requires a waiver artifact **and** an owner-signed row
   here.
> **Doučko note.** Upstream evidence links point at the Project Factory repo
> pinned in `factory/UPSTREAM.md`. Script names below are the installed
> names (`scripts/<name>.mjs`); each is the Doučko adaptation of the upstream
> `<name>.reference.mjs`. Adaptation did not change any check's verdict
> semantics except where the History table says so.

## Current rung per check

| Check | Status | Since | Evidence | Promotion criterion |
|---|---|---|---|---|
| `check-traceability` | hard (grandfathered upstream) | 2026-09-28 | upstream field study; adapted: contract paths, store + local specs, per-repo ownership | n/a — demotion needs owner-signed row |
| `check-trajectory` | hard (grandfathered upstream) | 2026-09-28 | upstream field study; adapted: per-stack module scope | n/a |
| `check-recordings` (frontend) | hard (grandfathered upstream) | 2026-09-28 | upstream field study + PD-4 | n/a |
| `check-coverage-ratchet` | hard (grandfathered upstream) | 2026-09-28 | upstream field study; adapted: JaCoCo XML reader (backend) | n/a |
| `check-eval-ratchet` (frontend) | hard (grandfathered upstream) | 2026-09-28 | upstream field study | n/a |
| `check-a11y` (frontend) | hard (grandfathered upstream) | 2026-09-28 | upstream field study — pairs with vision-verify | n/a |
| `qa-verify` (battery runner) | hard (grandfathered upstream) | 2026-09-28 | upstream pixel forensics; adapted: battery from `factory.config.json` | n/a |
| `gate-status` (aggregator) | hard (grandfathered upstream) | 2026-09-28 | upstream field study; adapted: role-excluded gates render `n/a (<repo> role)`, never PASS | n/a |
| `check-acceptance-methods` | **hard from day one** (upstream exception) | 2026-09-28 | upstream design mechanism 2; adapted: per-repo ownership scope, Maven mechanisms, Verification-column parsing | n/a — demotion needs owner-signed row |
| `check-workspace` (docs) | **experimental** (new in Doučko) | 2026-09-28 | smoke-run on the real workspace at G0 (pre-phase: SKIP-pending) | ≥ 2 truthful runs after G3 with 0 false positives, and an executed red→green proof (orphan method / unowned FR), recorded here |
| `check-process-ratchet` | **experimental** | 2026-09-28 | upstream `tests/ratchet.test.mjs` | ≥ 2 truthful full-delivery runs, 0 false positives; then CI adds `--strict` |
| `check-factory-integrity` | hard for gate-bearing drift; warn for workflows | 2026-09-28 | upstream design mechanism 8; adapted: `factory.config.json` + `scripts/lib/` + hook scripts are gate-bearing | n/a |
| `install.mjs --check` (harness drift) | soft | 2026-09-28 | new in Doučko: installed copies vs `factory/` source | hard after ≥ 2 truthful runs |
| `ledger` (emitter) | soft — permanently, by design | 2026-09-28 | upstream mechanism 1 | not on the hard ladder |
| `ledger-report` (digest) | soft | 2026-09-28 | upstream mechanism 6 | digest stays soft |
| `correct` (correction intake) | hard once a correction artifact exists | 2026-09-28 | upstream mechanism 4 | n/a |
| `record-demos` (frontend) | n/a — artifact producer | 2026-09-28 | feeds `check-recordings` | not on the ladder |

## History (append-only — newest first)

| Date | Check | Change | Evidence | Signed |
|---|---|---|---|---|
| 2026-09-28 | all | Adopted from upstream Project Factory @ 0d7286a1 and adapted for 3 repos (docs store + Kotlin/Maven backend + React/Vite frontend); `check-workspace` registered **experimental**; parity/visual-fidelity checks not adopted (no pixel-parity requirement) | ADR-0002; factory/UPSTREAM.md | — |
