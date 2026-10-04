# Design — implement-answer-checking-api

## Goals / Non-goals

Goals: every verdict-producing requirement of the store spec `answer-checking`
(verdicts and normalized values, numeric notation, length and exact
arithmetic, the seven answer types, alternatives, tolerance, format tips),
the golden table (FR-23) and the checker's timing budget (NFR-5).

Non-goals: the submission endpoint and its whole-submission refusal (FR-22,
slice 7 practice-flow), the admin answer tester and re-evaluation endpoints
(slices 5 and 9), validation of answer specs at authoring time (slice 5). The
checker trusts its spec; an accepted value it cannot read never matches.

## Key decisions

1. **Pure module, own domain types.** `cz.doucko.checking` mirrors the
   contract's `AnswerSpec`, `AnswerType`, `Verdict` and `PartResult` field
   names in plain Kotlin classes instead of using the generated models, so
   the module needs nothing outside `java.*` and `kotlin.*`. Slices 5 and 7
   map at their web adapters. `CheckingContractAlignmentTest` keeps the enum
   values equal to the pinned `openapi.yaml`.
2. **Exact arithmetic.** Every number becomes a `Rational` over `BigInteger`
   (a decimal `d` digits long is `n / 10^d`). Equality and tolerance distance
   are exact; there is no `Double` anywhere, so `3,15` against `3.14 ± 0.01`
   lies on the boundary and is `CORRECT`.
3. **Length limit first.** Free-text input longer than 100 code points after
   trimming is `UNPARSEABLE` before any parsing, which also bounds the
   `BigInteger` sizes and the regex work (NFR-5).
4. **Hand-written scanners, not one regex per type.** Numeric notation is one
   regex for sign, grouped or plain integer digits and fractional digits.
   Fractions and units split the input procedurally (last slash, the digit
   run right before it, the first letter or `°` after the number), which
   makes `12 500/3` and `24 2cm` decidable as the spec requires.
5. **Format tips from the catalog, with a checked example.** The catalog
   holds one tip per type with a `{0}` example slot. The example is one fixed,
   unusual value per type (`4827`, `4827,27`, `48/29`, `4827,27 <unit>`). Only
   when the part does not grade it `WRONG` is it replaced by a computed value
   above every accepted magnitude plus the tolerance, graded `WRONG` before
   use. There is no candidate list to skip through, so the tip is no oracle
   over a set of values and never shows an ungraded example (review F01, F02,
   F04). A tip over 120 characters (a long unit) uses `checking.tip.short`.
   Tips are Czech (the child reads them), unlike the English API problem
   titles in the same catalog.
6. **Module marker.** `CheckingModule` carries `@ApplicationModule` like
   `AdminModule`. It is the only class in the package that references Spring
   Modulith, a compile-time annotation. `CheckingPurityTest` (ArchUnit)
   allows only `java..`, `kotlin..`, `org.jetbrains.annotations..` and the
   module itself for every other class.

## Module and hexagon placement

- `checking/AnswerChecker.kt` and `checking/CheckingModel.kt`: the public API
  (facade and types) other modules will call.
- `checking/domain/`: `Rational`, `NumericNotation`, `FractionNotation`,
  `UnitNotation`, `TextMatching`, `FormatTips` (internal to the module).
- No `application/` or adapters: there is no use case, port or endpoint yet.

## Data model

None. The checker stores nothing.

## Error handling

The checker never throws for any input or spec: unreadable input is
`UNPARSEABLE` with a tip, and an unreadable accepted value never matches.
The property test runs random strings against every type to prove it.

Defined fallbacks for inputs the API layer normally refuses first: a raw input
whose shape does not match the part type (a string for a CHOICE_* part, a
selection for any other) is `UNPARSEABLE`; a tolerance that is missing,
unreadable or negative means exact comparison; an `exactDecimals` outside
0 to 6 never matches (`WRONG`) and never throws. Authoring validation
(slice 5) refuses such specs with 400, so these are safety nets, not
features.

## Risks and mitigations

- Catalog key missing at runtime: a silent fallback would hide the bug, so
  `FormatTips` reads every key once when the class loads and fails fast, and
  the golden table exercises every tip key.
- Regex backtracking on hostile input: the 100-code-point limit runs first,
  and the timing test checks the p95.
