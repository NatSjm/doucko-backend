## 1. Contract and schema

- [x] 1.1 Confirm the `contract/` pin (docs `4c87d67`, API 0.5.1) defines `AnswerSpec`, `AnswerType`, `Verdict` and `PartResult` as the checker uses them.
- [x] 1.2 No Flyway migration (the checker stores nothing).

## 2. Failing tests first (red)

- [x] 2.1 `GoldenTableTest` (FR-14 to FR-21, FR-23): at least 150 self-authored cases in `src/test/resources/checking/golden-table.jsonl`, at least 5 per verdict-producing requirement, every scenario input of the store spec, each case named by id; every UNPARSEABLE tip satisfies rules 1 to 4 and no other case has a tip.
- [x] 2.2 `AnswerCheckerPropertyTest` (FR-14, FR-15, FR-16, FR-19): grouping and sign variants of random integers, comma and dot with trailing zeros, scaled fractions, text case and whitespace, determinism and no exception for random input, tip examples are `WRONG`.
- [x] 2.3 `CheckerTimingTest` (FR-23, NFR-5 derived budget): every golden case 100 times, p95 below 5 ms.
- [x] 2.4 `CheckingPurityTest` (FR-23): ArchUnit, the module depends on nothing but `java`, `kotlin` and itself; `ModularityTest` stays green.
- [x] 2.5 `CheckingContractAlignmentTest` (FR-14): the domain enums equal the pinned contract's `AnswerType` and `Verdict`.
- [x] 2.6 Run the tests against compile-only stubs and confirm RED on assertions. Evidence: `docs/qa/evidence/slice3-red-run.md`.

## 3. Domain (green)

- [x] 3.1 `Rational`, `NumericNotation` (sign, grouping, decimal separator, length limit).
- [x] 3.2 INTEGER, DECIMAL, FRACTION, NUMBER_WITH_UNIT, CHOICE_*, TEXT; alternatives and tolerance.
- [x] 3.3 `FormatTips` from `messages.properties` with a checked example.

## 4. Adapters

- [x] 4.1 None in this slice (no endpoint; slices 5 and 7 add them).

## 5. Validation, review, archive

- [x] 5.1 `npm run qa:verify` green.
- [x] 5.2 `npx openspec validate implement-answer-checking-api --strict`.
- [ ] 5.3 review-gate workflow; disposition the findings in `review-findings.json`; re-run 5.1.
- [x] 5.4 Smoke: no endpoint, so the smoke is the golden table and timing run inside `mvnw verify` (counts recorded in `docs/qa/evidence/slice3-checker-run.md`).
- [x] 5.5 Update `docs/current-state.md`.
- [ ] 5.6 Archive; commit with `Slice: implement-answer-checking-api` + `Refs: FR-14, FR-15, FR-16, FR-17, FR-18, FR-19, FR-20, FR-21, FR-23`.
