# Slice 3 (implement-answer-checking-api) — RED run, task 2.6

- **Date:** 2026-10-04 (Europe/Prague)
- **Command:** `node scripts/mvnw.mjs -B -q test -Dtest='GoldenTableTest,AnswerCheckerPropertyTest,CheckerTimingTest,CheckingPurityTest,CheckingContractAlignmentTest' -Dsurefire.failIfNoSpecifiedTests=false`
- **Against:** compile-only stubs: the public model (`AnswerSpec`, `RawInput`, `CheckResult`, `AnswerType`,
  `Verdict`), the `CheckingModule` marker, and `AnswerChecker.check` returning
  `CheckResult(UNPARSEABLE, null, null)` for every input. No parser, no catalog keys.

| Test class | Run | Failed | Why |
|---|---|---|---|
| GoldenTableTest | 215 | 212 | assertions: every one of the 212 golden cases gets `UNPARSEABLE` with no tip (CORRECT/WRONG expected, or the tip rules fail on a missing tip). The 3 table-shape tests (size ≥ 150, ≥ 5 per requirement, the NBSP regression case is present) pass, as they check the fixture, not the checker. |
| AnswerCheckerPropertyTest | 6 | 6 | assertions: `UNPARSEABLE` instead of `CORRECT`/`WRONG`; the totality property fails on the missing tip. |
| CheckerTimingTest | 1 | 1 | assertion: 21 200 verdict mismatches against the golden table (timing itself is trivially fast on the stub). |
| CheckingPurityTest | 1 | 0 | passes on the stub, as expected: the stub is already pure. It guards the implementation. |
| CheckingContractAlignmentTest | 1 | 0 | passes on the stub, as expected: the enums were written from the contract. It guards drift. |

No compile errors. Totals: 224 run, 219 failed, 0 errors.
