# answer-checking Specification

## Purpose
Backend realization of the store spec answer-checking: the pure checker module `cz.doucko.checking`, its
format tips from the message catalog, and the golden table and timing checks that pin its verdicts in the build.

## Requirements

### Requirement: Pure checker module

The backend SHALL implement the store spec `answer-checking` verdict rules
(FR-14 to FR-21) in the module `cz.doucko.checking` as a pure function
`AnswerChecker.check(spec, input)`. Apart from its Spring Modulith package
marker, the module SHALL depend only on `java`, `kotlin` and itself: no
Spring, database, network, file or AI access while checking (NFR-5). The
module's `AnswerType` and `Verdict` values SHALL equal the pinned contract's.

#### Scenario: Dependency rule

- **WHEN** the backend test suite runs `CheckingPurityTest` and `ModularityTest`
- **THEN** no class of `cz.doucko.checking` other than `CheckingModule` depends on a package outside `java`, `kotlin`, `org.jetbrains.annotations` and `cz.doucko.checking`, and the module boundaries verify

#### Scenario: Contract enums

- **WHEN** `CheckingContractAlignmentTest` reads `contract/api/openapi.yaml`
- **THEN** `AnswerType` and `Verdict` list exactly the module's enum values

### Requirement: Format tips from the message catalog

Format tips SHALL be read from `messages.properties` (keys `checking.tip.*`)
through `java.util.ResourceBundle`, never hard-coded (NFR-9). The numeric
tips' example SHALL be one fixed value per type, or, when the part does not
grade that value `WRONG`, a computed value outside every accepted value plus
the tolerance; every example SHALL be graded `WRONG` before it is shown.

#### Scenario: Tip for an unreadable decimal

- **GIVEN** a DECIMAL part whose expected value is `5.2`
- **WHEN** the input `5,,2` is checked
- **THEN** the verdict is `UNPARSEABLE`, the tip is Czech, at most 120 characters, ends with `třeba <example>.`, and `<example>` checked against the part is `WRONG`

### Requirement: Golden table and timing in the build

The backend test suite SHALL run a golden table of at least 150
self-authored cases (FR-23) from `src/test/resources/checking/golden-table.jsonl`,
listed in `fixtures/PROVENANCE.md`, with at least 5 cases for each of the 12
verdict-producing requirements, every scenario input of those requirements,
and a test name that names each case. It SHALL also time every golden case
100 times and fail when the p95 is 5 ms or more.

#### Scenario: Golden table run

- **WHEN** `mvnw verify` runs
- **THEN** `GoldenTableTest` executes at least 150 cases and all pass, and a changed verdict fails the build naming the case id and input

#### Scenario: Timing

- **WHEN** `CheckerTimingTest` runs every golden case 100 times
- **THEN** no check throws and the 95th percentile is below 5 ms
