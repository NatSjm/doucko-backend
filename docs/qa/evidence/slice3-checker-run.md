# Slice 3 (implement-answer-checking-api) — checker run, task 5.4

- **Date:** 2026-10-04 (Europe/Prague)
- **Command:** `npm run qa:verify` (runs `node scripts/mvnw.mjs -B verify`); full log summary in
  `docs/qa/automated-verification-latest.md`.
- There is no endpoint in this slice, so the smoke is the checker run inside the build.

| Check | Result |
|---|---|
| Golden table (`GoldenTableTest`) | 217 cases from `src/test/resources/checking/golden-table.jsonl`, all pass; ≥ 5 per each of the 12 verdict-producing requirements; plus 3 table-shape tests |
| Property tests (`AnswerCheckerPropertyTest`) | 6 properties, fixed seed 20261004, all pass |
| Timing (`CheckerTimingTest`) | 21 700 checks (217 cases × 100), p95 37–73 µs, max 9–15 ms (first-call JIT outliers), budget p95 < 5 ms (NFR-5 300 ms round trip) |
| Purity (`CheckingPurityTest`, ArchUnit) | pass: `cz.doucko.checking` depends only on `java.lang/math/text/util`, `kotlin`, itself |
| Modules (`ModularityTest`, Spring Modulith) | pass with the new `checking` module |
| Scenario inputs (`GoldenScenarioCoverageTest`) | pass: every WHEN-line input of the 12 requirements in the pinned store spec is a golden case |
| Contract enums (`CheckingContractAlignmentTest`) | pass: `AnswerType`, `Verdict` equal `contract/api/openapi.yaml` |
| Whole suite | 347 unit tests, 25 integration tests (after the review fixes), 0 failures |
