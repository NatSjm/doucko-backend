# progress-stats Specification

## Purpose
Backend behavior of slice 9 of the progress-stats store spec: weekly statistics computed from the whole attempt log and the opened hints in Europe/Prague weeks, the flag queue with the attached attempt as logged, and re-evaluation of a reported attempt, which records the new verdict next to the original and adds XP only through the append-only ledger.

## Requirements

### Requirement: Weekly figures are computed from the log in Europe/Prague weeks

`getStats` SHALL return exactly `weeks` entries (absent: 4), oldest first, the
current week last, each identified by its Monday in Europe/Prague. Sessions
SHALL be built from every logged attempt of every run (replays included),
split where consecutive attempts are more than 30 minutes apart, and counted
in the week of their first attempt; `tasksPerSession` lists the distinct
tasks of each session in order and `qualifyingSessions` counts those with at
least 10. `hintsUsed` SHALL count opened hints by the week they were opened
and `revealed` the second wrong attempts by their week. A week runs from
Monday 00:00 to the next Monday 00:00 local time, so weeks containing a DST
change keep whole local days (FR-40, OQ-14).

#### Scenario: Gaps of 20, 30 and 35 minutes

- **GIVEN** logged attempts with gaps of 20 minutes, exactly 30 minutes and 35 minutes
- **WHEN** the stats are computed
- **THEN** the 20- and 30-minute gaps keep one session and the 35-minute gap starts a new one

#### Scenario: Session across midnight on Sunday

- **GIVEN** a session from Sunday 23:40 to Monday 00:15 Europe/Prague
- **WHEN** the stats are computed
- **THEN** it counts once, in the week whose Monday precedes that Sunday

#### Scenario: Weeks around the DST changes

- **GIVEN** attempts on Sunday 2026-03-29 at 23:30 and Monday 2026-03-30 at 00:30 Europe/Prague (after the spring change), and on Sunday 2026-10-25 at 23:30 and Monday 2026-10-26 at 00:30 (after the autumn change)
- **WHEN** the stats are computed
- **THEN** the Sunday attempts count in the weeks starting 2026-03-23 and 2026-10-19 and the Monday attempts in the weeks starting 2026-03-30 and 2026-10-26

#### Scenario: Invalid week counts

- **GIVEN** an admin session
- **WHEN** `getStats` is called with `weeks` empty, `0`, `53`, `-1`, `4.5`, `4,5`, `1 000`, `٤`, `2147483648` or 40 digits
- **THEN** each answers 400 problem+json with `errors[].field` = `weeks`

### Requirement: Re-evaluation goes through the XP ledger

A re-evaluation that improves a task's first-resolution outcome SHALL store
the new verdicts in an append-only re-evaluation record next to the original
attempt, add one ledger row with the XP difference (never negative) and a
completion-bonus row for every served mission it completes for the first
time, and leave every rank-up for `HqView.pendingRankUp`. Practice state SHALL
read the re-evaluated verdict of a first-play-through attempt, so the task is
resolved for the child. A replay attempt's re-evaluation SHALL award nothing
and change no outcome (FR-41, OQ-20, OQ-17).

#### Scenario: In-progress task resolved by a re-evaluation

- **GIVEN** a task whose attempt 1 was wrongly marked `WRONG`, reported, and whose answer spec the admin fixed
- **WHEN** the admin re-evaluates the report
- **THEN** the result has `changed` true, `xpAwarded` 10, `newTaskState` `CORRECT_FIRST_TRY`, the report is `FIXED`, and an attempt on that task answers 409 because it is resolved

#### Scenario: Re-evaluation XP is not shown as attempt XP

- **GIVEN** a task revealed for 1 XP and re-evaluated for +4 XP
- **WHEN** the child reopens the task screen
- **THEN** the latest attempt's feedback still shows the 1 XP it earned, and HQ shows the total including the +4

### Requirement: Flag queue entries show the logged attempt

`listReports` SHALL return every report with its status, `OPEN` first and
each group oldest first, with the attached attempt as logged: raw input per
typed part verbatim, normalized input, the original verdicts, hints used,
attempt number, replay flag, timestamp and the re-evaluated verdicts when
recorded (FR-41, FR-42).

#### Scenario: Locked part on attempt 2

- **GIVEN** a two-part task whose part a was correct on attempt 1 and a report after attempt 2
- **WHEN** the admin lists the queue
- **THEN** the entry's attempt has `attemptNo` 2, `rawInput` without part a, and `verdicts` with part a `CORRECT`
