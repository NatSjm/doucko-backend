# Design — release-review-api

## Goals / Non-goals
- Goal: one whole-history review of the backend before the release (PLAYBOOK Phase 7), every finding dispositioned per PD-15.
- Goal: G7 release commands green, contract pin current.
- Non-goal: new behaviour, endpoints, migrations or contract changes.

## Key decisions
1. **Review split by module.** A single whole-repo run exhausted the per-step budget (security and spec-compliance never reported) and was stopped without a record; the review ran instead as four sonnet runs over `38b56c8..HEAD` restricted by path (a: admin/api/profile/shared/checking, b: content, c: practice, d: tools/resources/compose/pom). Each run's `review-findings.json` was saved separately and merged.
2. **NUL is refused at the edge in practice, mapped by SQLSTATE in content.** Child input is validated in `PracticeReaders` / `ReportRules` (field problem `nul-character`, 400) so nothing reaches Postgres. Content has many text fields; instead of a check in each, `ContentProblemAdvice` maps `DataIntegrityViolationException` by SQLSTATE: class 22 (data exception) → 400 `content-text-invalid`, otherwise the 409 conflict. The transaction rolls back, so nothing is stored; only the SQLSTATE is logged (NFR-3).
3. **Waivers over hardening.** Minor findings that are defence in depth (DB triggers on TRUNCATE, restore quiesce, storage-volume backup, rate limits) or excluded by the store spec (lockout, TLS) are waived with reasons and left as candidates for UAT or later changes.

## Module + hexagon placement
`practice/adapter/in/web/PracticeReaders.kt`, `practice/domain/Rules.kt` (ReportRules), `content/adapter/in/web/ContentProblemAdvice.kt`, `messages.properties`.
