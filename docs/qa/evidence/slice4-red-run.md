# Slice 4 (implement-child-profile-api) — RED run, task 2.7

- **Date:** 2026-10-04 (Europe/Prague)
- **Commands:** `node scripts/mvnw.mjs -B test` (whole unit suite), then
  `node scripts/mvnw.mjs -B test -Dtest='RequestContentNotLoggedTest*'`
- **Against:** compile-only stubs: `DisplayNamePolicy.check` accepts anything untrimmed and
  `message` is empty, `SettingsValidator.validate` always returns an empty valid patch,
  `ProfileSettingsService` returns the defaults and writes nothing, `ChildProfileInitializer`
  does nothing, `SettingsBodyAdvice` passes the body through to the generated DTO, and
  `ProfileProblemAdvice` has no handlers. Migration `V1__child_profile.sql` and the
  `doucko.profile.task-locales` configuration were in place.

| Test class | Run | Failed | Why |
|---|---|---|---|
| DisplayNamePolicyTest | 9 | 9 | assertions: nothing trimmed or refused, empty messages |
| SettingsValidatorTest | 18 | 16 (3 failures, 13 errors) | assertions; the 13 errors are `ClassCastException` where a test expects `Validation.Invalid` and the stub returns `Valid` (a wrong outcome, not a compile error) |
| ChildProfileStartupTest | 13 | 9 | assertions: no profile created, no refusal |
| ProfileSettingsTest (incl. `WithoutConfiguredLocales`) | 36 | 26 | assertions: no change stored, 200 instead of 400/413/422, the admin 404 case found the stored setting reset |
| RequestContentNotLoggedTest | 3 | 1 | `AtTrace`: Hibernate Validator's `JPATraversableResolver` logs the bound `ProfileSettingsPatch` (with the submitted locale) at TRACE. A real NFR-3 leak the new endpoint case exposed; fixed by capping `org.hibernate.validator` in `RequestContentLogGuard` and by never binding submitted values into the generated DTO |
| ProfileContractTest | 1 | 0 | passes on the pinned contract as expected (a guard, not new behavior) |

The 10 passing cases in the profile classes check refusal paths the stubs happen to satisfy
(e.g. "an existing profile starts normally"). No compile errors. The integration tests
`ProfileSettingsIT` and `ChildProfileStartupIT` (Docker) were not part of this run; they run in
`mvnw verify` after the implementation.
