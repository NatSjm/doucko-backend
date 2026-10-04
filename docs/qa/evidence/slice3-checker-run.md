# Slice 3 (implement-answer-checking-api) — checker run, task 5.4

- **Date:** 2026-10-04 (Europe/Prague)
- **Command:** `npm run qa:verify` (runs `node scripts/mvnw.mjs -B verify`); full log summary in
  `docs/qa/automated-verification-latest.md`.
- There is no endpoint in this slice, so the smoke is the checker run inside the build.

| Check | Result |
|---|---|
| Golden table (`GoldenTableTest`) | 212 cases from `src/test/resources/checking/golden-table.jsonl`, all pass; ≥ 5 per each of the 12 verdict-producing requirements; plus 3 table-shape tests |
| Property tests (`AnswerCheckerPropertyTest`) | 6 properties, fixed seed 20261004, all pass |
| Timing (`CheckerTimingTest`) | 21 200 checks (212 cases × 100), p95 36–51 µs, max 8–14 ms (first-call JIT outliers), budget p95 < 5 ms (NFR-5 300 ms round trip) |
| Purity (`CheckingPurityTest`, ArchUnit) | pass: `cz.doucko.checking` depends only on `java.lang/math/text/util`, `kotlin`, itself |
| Modules (`ModularityTest`, Spring Modulith) | pass with the new `checking` module |
| Contract enums (`CheckingContractAlignmentTest`) | pass: `AnswerType`, `Verdict` equal `contract/api/openapi.yaml` |
| Whole suite | 335 unit tests, 25 integration tests, 0 failures |
