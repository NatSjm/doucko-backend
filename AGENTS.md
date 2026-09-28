# Doučko — backend — Agent Rules

Kotlin + Spring Boot 3 + JVM 21 + **Maven** + Postgres 16 + Flyway. One of
three repos: product truth lives in the **docs store** (`../docs`, OpenSpec
store `doucko`, pinned here as the `contract/` submodule); the UI lives in
`../frontend`.

**This may NOT be the Spring Boot / Kotlin you know.** Read the docs for the
versions in `pom.xml` before using an API; heed deprecations.

Requirements: `contract/docs/requirements.md` (until the pin exists:
`../docs/docs/requirements.md`). Which requirements THIS repo owns:
`contract/docs/mvp-capability-plan.md`, table `| Req | Slice | Repo(s) |`.

## Project Handoff Protocol

Before any substantive change read: `docs/current-state.md` (this repo), the
capability plan, the store spec(s) your change cites
(`npx openspec show <cap> --type spec --store doucko`), `contract/docs/adr/`
and `docs/adr/`. Update `docs/current-state.md` at every milestone
(Europe/Prague; keep `Current phase: Phase <N>` / `Last completed gate: G<N>` exact).

## Delivery harness

Adapted Project Factory — playbook `../docs/factory/PLAYBOOK.md`, gates
`../docs/factory/GATES.md`. Installed here: `scripts/` (gate scripts +
`mvnw.mjs`), `.githooks/`, `.claude/agents/`, `.claude/workflows/`,
`factory.config.json`. Never hand-edit installed files — change
`../docs/factory/` and run `npm run factory:install`.

```bash
npm run qa:verify        # battery: traceability, acceptance, mvnw -B verify, coverage ratchet, OpenSpec
npm run gate:status      # G0–G8
npm run verify           # node scripts/mvnw.mjs -B verify (works in cmd, PowerShell, bash, CI)
npx openspec doctor      # reference doucko must be ok
```

## OpenSpec here

- Changes: `openspec/changes/implement-<capability>-api/` from
  `../docs/factory/templates/openspec/change.backend.md`.
- Product specs are NOT copied here — `references: [doucko]` gives agents the
  index; fetch with `npx openspec show <spec> --type spec --store doucko`.
- An API change is a docs-store change first (spec delta + `api/openapi.yaml`),
  then bump `contract/`.

## Module conventions

- Modular monolith (NFR-11): `src/main/kotlin/cz/doucko/<module>/` for
  `content`, `checking`, `practice`, `progress`, `tutor`, `storage`, `shared`.
  Cross-module calls only via a module's public API; Spring Modulith
  `verify()` guards it.
- Hexagonal per module: `domain/` (pure Kotlin) · `application/` (use cases,
  ports) · `adapter/in/web` (implements generated `*Api` interfaces) ·
  `adapter/out/persistence`.
- `checking` has **no Spring dependency** — pure functions, golden table ≥ 150
  cases (FR-23), property tests.
- Ports for what changes later: `TutorPort` (FR-44), `FileStoragePort`,
  `TranslationProvider`.
- Generated code (`target/generated-sources/openapi`) is never edited (NFR-12).
- Flyway migrations are append-only; JSONB for answer specs / attempt payloads
  with typed Kotlin models.

## Correctness rules

- No user input may produce a raw 500: validation/constraint errors → RFC 7807
  problem responses with human messages; no driver internals.
- Child endpoints never mutate content and never leak answer keys before the
  reveal (FR-27, NFR-1). Admin endpoints require the admin (Spring Security).
- UNPARSEABLE input never consumes an attempt (FR-22); every attempt is
  persisted immediately (FR-31); XP is an append-only ledger, never reduced (FR-38).
- Dates for stats use Europe/Prague local weeks (§5 metric).
- No AI/LLM calls in MVP (TC-6).

## Test-first

Tests are written from the spec and seen RED before implementation, then made
GREEN; never weakened. Annotate every test with `// @trace FR-x` (only ids
this repo owns); regressions `// @trace BUG-n`. Unit `*Test.kt` (Surefire),
integration `*IT.kt` (Failsafe + Testcontainers Postgres 16).

## Never commit

Workbook content — text, images, answer keys, PDFs (NFR-4); the child's name,
photos or any PII (NFR-3) in code, fixtures, logs or screenshots; `.env`
files or secrets. The pre-commit hook enforces the obvious cases; you enforce
the rest. Self-authored PDF fixtures go under `fixtures/self-authored/`.

## Environment notes

- Windows dev machine (PowerShell / Git Bash): use `node scripts/mvnw.mjs …`
  or `npm run verify`, never a bare `./mvnw` in npm scripts.
- JDK 21 required (the machine default may be older — set `JAVA_HOME`).
- Docker Desktop required for Testcontainers and `docker compose`.
- Commits touching `src/main/` need a `Slice: <change-id>` or `Refs: FR-x`
  trailer (commit-msg hook).

<!-- BEGIN-FACTORY-LESSONS -->
<!--
  Managed region: `npm run factory:install` upserts cross-project lesson
  blocks here (BEGIN-LESSON-<id> / END-LESSON-<id>). Manual edits inside a
  lesson block are preserved. Do not remove these outer markers.
-->
<!-- BEGIN-LESSON-vacuous-pass-not-earned -->
### Lesson: a PASS over zero evidence is NOT-EARNED (vacuous-pass-not-earned, v1)

- Never report a gate or check as PASS when its evidence scope is 0 ("Scope: 0
  clip(s)", empty archive, no eval results) while product code exists (Doučko:
  `factory.config.json` productCode — backend `src/main/`, frontend `src/`
  minus `src/design-system/`). Render it **NOT-EARNED**
  and exit non-zero.
- Before product code exists, an empty scope is **SKIP-pending**: print it
  explicitly; it is visible and never counted as PASS.
- Never fold SKIP / 0-scope results into an overall "Pass" summary
  (`qa-verify`, `gate-status`). Field evidence: `worst()` folded SKIP into
  PASS and G4–G8 rendered green over literally nothing
  (2026-07-02-pixel-perfect-forensics.md, RC2).
<!-- END-LESSON-vacuous-pass-not-earned -->
<!-- BEGIN-LESSON-declared-method-needs-mechanism -->
### Lesson: a declared acceptance method needs an executable mechanism (declared-method-needs-mechanism, v1)

- Every FR/NFR that declares a verification method (pixel-diff, e2e,
  recording, a11y, eval, ...) must resolve to a real, executable, non-stub
  mechanism BEFORE the build phase starts: an installed check script, or a
  package.json script whose body does not match
  `/^echo |^true$|not yet configured/i`.
- A spec file that restates the requirement is NOT a mechanism. Field
  evidence: NFR-19 encoded "≥ 99% pixel match" while no pixel-diff tool
  existed anywhere and `test:e2e` was an echo stub exiting 0
  (2026-07-02-pixel-perfect-forensics.md, RC1).
- If a declared method has no mechanism, do not proceed: implement the check,
  or record an explicit human waiver — never let the declaration float
  unverifiable into the build.
<!-- END-LESSON-declared-method-needs-mechanism -->
<!-- BEGIN-LESSON-done-claims-need-evidence -->
### Lesson: done-claims need evidence pointers (done-claims-need-evidence, v1)

- Never write strong completion language ("Convergence reached", "all gates
  pass", "verification-only", "Overall result: Pass", "ready for release /
  sign-off", "done", "complete") into current-state.md, PR bodies, or handoff
  docs unless the same line carries a resolvable evidence pointer — a path
  that exists on disk (e.g. `docs/qa/automated-verification-latest.md`) and
  is fresh — or an explicit "Scope NOT delivered" section.
- The verdict belongs to exit-coded checks, not narrative. Field evidence:
  "Convergence reached … verification-only" was written while the same file
  admitted the formal acceptance was never run
  (2026-07-02-pixel-perfect-forensics.md, RC6).
- When you catch an unbacked claim, treat it as a correction event: file it,
  do not silently rewrite it.
<!-- END-LESSON-done-claims-need-evidence -->
<!-- END-FACTORY-LESSONS -->
