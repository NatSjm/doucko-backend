# Implement answer checking (backend)

## Why

Slice 3 of the capability plan (`contract/docs/mvp-capability-plan.md` §4.3).
Store spec: `answer-checking`. Practice (slice 7), the admin answer tester
(slice 5) and re-evaluation all need one deterministic checker. This change
builds it as a pure module and owns FR-14 to FR-21 and FR-23. It travels
NFR-4 (a self-authored golden table, no workbook content) and NFR-5 (the
checker's share of the 300 ms round trip).

## What Changes

- New module `cz.doucko.checking` with no Spring or database dependency:
  `AnswerChecker.check(spec, input)` returns a verdict (`CORRECT`, `WRONG`,
  `UNPARSEABLE`), the normalized value and, for unreadable input, a Czech
  format tip.
- Types: INTEGER, DECIMAL (decimal comma or dot, space, no-break space and
  thin-space grouping, trailing zeros, `exactDecimals`), FRACTION (plain,
  mixed, decimal; `allowEquivalent`, `requireLowestTerms`),
  NUMBER_WITH_UNIT (exponent spellings, case, required or optional unit, no
  conversion), CHOICE_SINGLE, CHOICE_MULTI and TEXT (NFC, case, whitespace,
  optional diacritics-strict). Accepted alternatives and an exact absolute
  tolerance on rational arithmetic.
- Format tips come from the message catalog (`messages.properties`, keys
  `checking.tip.*`, NFR-9 backend mechanism), read through `java.util`
  `ResourceBundle`, not Spring.
- A golden table of at least 150 self-authored cases
  (`src/test/resources/checking/golden-table.jsonl`, listed in
  `fixtures/PROVENANCE.md`), property tests and a timing test.

## Impact

- Store spec: answer-checking · Contract pin: docs `4c87d67` (API 0.5.1), no contract change
- Modules: `cz.doucko.checking` (new) · Flyway: none · Endpoints: none (slices 5 and 7 call the checker)
- Dependencies: slice 1 (message catalog, provenance manifest); no new Maven dependencies
