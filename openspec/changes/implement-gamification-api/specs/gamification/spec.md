## ADDED Requirements

### Requirement: Task XP is decided on the first resolution

`submitAttempt` SHALL award task XP only when the attempt resolves the task in
the primary scope for the first time, from the configured values:
`CORRECT_FIRST_TRY` and `CORRECT_SECOND_TRY` earn their award minus the
configured hint cost per hint opened before that attempt, never below the
configured minimum; `REVEALED` earns the revealed award (at least the
minimum). The award SHALL be returned as `xpAwarded`, stored as one ledger row
and added to `HqView.totalXp`. Wrong attempts, `UNPARSEABLE` submissions,
replay attempts and attempts on a task resolved in another mission SHALL award
0 and change nothing (FR-34, OQ-4, OQ-15).

#### Scenario: Award table over HTTP

- **GIVEN** the default configuration and fresh single-part tasks
- **WHEN** they are resolved correct on attempt 1 with 0, 2 and 5 hints opened, correct on attempt 2 with 0, 1 and 3 hints, and revealed with 0 and 3 hints
- **THEN** `xpAwarded` is 10, 6, 1, 5, 3, 1, 1 and 1, and `totalXp` is their sum

#### Scenario: Neutral submissions

- **GIVEN** a total of 40 XP and an unresolved task
- **WHEN** the child sends `5,,2`, then a wrong attempt 1
- **THEN** both responses carry `xpAwarded` 0 and `bonusXp` 0, the total stays 40, and a later correct attempt 2 without hints earns 5

#### Scenario: Hints after the resolution and replays earn nothing more

- **GIVEN** a task resolved correctly on attempt 1 for 10 XP in a complete mission
- **WHEN** the child opens its hints, replays the mission and answers every task correctly
- **THEN** the total is unchanged and every replay response has `xpAwarded` 0, `bonusXp` 0, empty `completedMissionIds` and `rankUp` null

### Requirement: Completion bonuses are paid once per mission

The first resolution of a task SHALL pay the configured bonus for every
published mission that contains the task and whose tasks are now all
resolved, once per mission ever: `bonusXp` is their sum and
`completedMissionIds` lists them. Publishing a mission whose tasks are all
resolved, or saving a published mission's task list so that every remaining
task is resolved, SHALL pay its bonus then if it was never paid, with no task
XP (FR-35, OQ-13, OQ-15, OQ-17, OQ-28).

#### Scenario: Shared task completes another mission

- **GIVEN** mission A with T1–T10 and mission B with T1, T2, T11, where T11 was resolved in B and T1 in A, and a total of 100 XP
- **WHEN** T2 is resolved in A correctly on attempt 1 without hints
- **THEN** the response has `xpAwarded` 10, `bonusXp` 20, `completedMissionIds` [B], `missionComplete` false, and HQ shows 130 XP with B complete

#### Scenario: One resolution completes two missions

- **GIVEN** missions A and B whose only unresolved task is the shared task T, and a total of 100 XP
- **WHEN** T is resolved correctly on attempt 1 without hints
- **THEN** `bonusXp` is 40, `completedMissionIds` holds A and B, and the total is 150

#### Scenario: Mission published already complete

- **GIVEN** a total of 100 XP and a draft mission C whose tasks were all resolved in other missions
- **WHEN** the admin publishes C, unpublishes it and publishes it again
- **THEN** the total is 120 after the first publish and stays 120, and the ledger holds one bonus row for C

### Requirement: XP and ranks come from a validated configuration file

The backend SHALL read the XP values and the rank ladder from one JSON
configuration file when it starts and SHALL refuse to start, with an error
naming the offending key, when a key is missing or unknown, when a value is
not a JSON integer in its range (task awards, hint cost and mission bonus
0..1000, minimum 1..1000, thresholds 0..1 000 000), when the ladder is empty,
does not start at 0 or is not strictly increasing, when a rank name is
missing, blank or longer than 50 characters, or when a key appears twice
(FR-34, FR-35, FR-36, FR-39, OQ-29).

#### Scenario: Invalid values name their key

- **GIVEN** the default file with one value replaced by `-1`, `2.5`, `"10"`, `"10,5"`, `"1 500"`, `"deset"`, `true`, `null` or `2147483648`, or with `xp.missionBonus` removed
- **WHEN** the backend starts
- **THEN** it refuses to start and the error names that key, and no XP is computed

#### Scenario: Invalid ladder names the rank entry

- **GIVEN** a ladder with thresholds 0, 400, 150, or 0, 150, 150, or a 2nd threshold of `150.5`, `"150"`, `-150`, `1000001`, null or absent
- **WHEN** the backend starts
- **THEN** it refuses to start and the error names `ranks[1]`

#### Scenario: Rank name limit and duplicate keys

- **GIVEN** the default file with the 2nd rank named by 50 characters, then by 51 characters, then with `hintCost` written twice
- **WHEN** the backend starts
- **THEN** it starts with the 50-character name, and refuses the 51-character name naming `ranks[1]` and the duplicate key

#### Scenario: Another file changes values and names, not behaviour

- **GIVEN** a file with a 1st-attempt award of 12, a mission bonus of 30 and other rank names
- **WHEN** a one-task mission is resolved correctly on attempt 1 without hints
- **THEN** the response has `xpAwarded` 12 and `bonusXp` 30, and HQ names the ranks from that file

### Requirement: Rank-ups are celebrated once

`AttemptResult.rankUp` SHALL name the highest rank reached when the attempt's
awards cross one or more thresholds and the attempt did not complete the run;
that celebration counts as played. When the attempt completed the run, the
crossing SHALL be offered by `MissionSummary.rankUp` and
`HqView.pendingRankUp` until `ackRankUp` acknowledges an index at least as high;
crossings outside a session (publish-time bonus) SHALL be offered by
`HqView.pendingRankUp` the same way. A played rank SHALL appear on the
summary as `rankReachedDuringRun` (FR-32, FR-36).

#### Scenario: Mid-mission rank-up

- **GIVEN** the default ladder, a total of 145 XP and a mission with two unresolved tasks
- **WHEN** one of them is resolved correctly on attempt 1 without hints
- **THEN** `rankUp` is rank 1 and a following `GET /api/child/hq` has `pendingRankUp` null

#### Scenario: Mission-complete rank-up waits for the acknowledgement

- **GIVEN** a total of 140 XP and a mission with one unresolved task
- **WHEN** it is resolved correctly on attempt 1 without hints (10 + 20 bonus)
- **THEN** `rankUp` is null and `missionComplete` true, the summary and HQ both offer rank 1 until `ackRankUp` with `rankIndex` 1, after which HQ `pendingRankUp` and summary `rankUp` are null and the summary's `rankReachedDuringRun` is rank 1

#### Scenario: A newer rank-up survives an older acknowledgement

- **GIVEN** a pending rank-up to rank 2
- **WHEN** the client acknowledges `rankIndex` 1
- **THEN** HQ still offers rank 2

### Requirement: Summary, briefing and HQ show the ledger

`getMissionSummary` SHALL report as `xpEarned` the sum of the ledger rows
caused by the run's attempts through that mission (0 for a replay);
`getMissionBriefing` SHALL report as `xpAvailable` the unresolved tasks at the
1st-attempt award plus the bonus when it was never paid; `getHq` SHALL report
the ledger total, the rank, the next rank (null at the top) and the solved
tasks (FR-32, FR-33, FR-37).

#### Scenario: Summary of a first completion and of a replay

- **GIVEN** a 10-task mission resolved with 7 correct on attempt 1, 2 on attempt 2 and 1 revealed, no hints
- **WHEN** the summary is read, the mission is replayed with every task correct, and the summary is read again
- **THEN** the first summary has `xpEarned` 101, `firstTryCount` 7 and `replay` false; the second has `xpEarned` 0, `firstTryCount` 10 and `replay` true, and the total is unchanged by the replay

#### Scenario: Practice stored before the ledger existed

- **GIVEN** tasks resolved and a mission completed in the primary scope with no ledger rows
- **WHEN** the backend starts
- **THEN** each resolved task gets its award from its resolving attempt and the complete mission its bonus, once; a second start adds nothing

#### Scenario: Available XP

- **GIVEN** a 3-task mission with one task resolved
- **WHEN** the briefing is read before and after the mission is completed
- **THEN** `xpAvailable` is 40 (2 × 10 + 20) and then 0

### Requirement: The XP ledger is append-only

XP SHALL be stored only as inserted ledger rows; the database SHALL refuse
any update or delete of a ledger row, and no request SHALL lower the total
(FR-38).

#### Scenario: Ledger rows cannot be changed

- **GIVEN** a ledger with a task award
- **WHEN** an `UPDATE` or `DELETE` of that row is attempted in the database
- **THEN** it fails and the row is unchanged

#### Scenario: XP never goes down

- **GIVEN** a sequence of attempts, reveals, hint openings, an answer-spec edit, a replay and a rank-up acknowledgement
- **WHEN** HQ is read after each step
- **THEN** each `totalXp` is greater than or equal to the previous one
