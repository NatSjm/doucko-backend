# Slice 5 (implement-authoring-tasks-api) — RED run, task 2.4

- **Date:** 2026-10-04 (Europe/Prague)
- **Command:** `node scripts/mvnw.mjs -B test` (whole unit suite; the Docker-backed `*IT` classes run
  in `mvnw verify` after the implementation)
- **Against:** compile-only stubs: `SourceRules.validate`, `TaskRules.validate` and
  `SpecRules.validate` return `Invalid(emptyList())`, `SpecRules.normalized` an empty list and
  `SpecRules.unit` null, `TaskRules.query` passes the raw query through, `PdfBoxInspector.pageCount`
  returns null, and `StorageRoot.check` accepts any root. The ports, use cases, JDBC stores,
  controllers, body advice, problem advice, migration `V2__content_authoring.sql`, configuration and
  message catalog were in place (plumbing; the rules under test were the stubs).

Totals: 537 run, 64 failures, 35 errors (all in content classes and the extended log-capture test).
Every pre-existing class stayed green (admin, checking, profile, platform, `UndeclaredPathTest`,
`ChildOperationsContractTest`).

| Test class | Run | Failed | Why |
|---|---|---|---|
| SpecRulesTest | 34 | 34 | assertions: `Validation.Invalid` with no errors where a valid spec or a specific field error is expected |
| TaskRulesTest | 15 | 15 | assertions: no task validates, no field errors, the query is not trimmed |
| SourceRulesTest | 5 | 3 | assertions; the 2 passing cases check `FileKind` and `Text`, which are not stubs |
| StorageAndPdfTest | 4 | 2 | assertions: page count null for a valid PDF; no refusal for a storage root inside the repository |
| SourceApiTest (incl. `WithGradeSeven`) | 17 | 10 | assertions: 400 without `errors` instead of 201, missing field errors; 1 error where the test reads `sourceId` from the refused upload |
| TaskApiTest (incl. `WithGradeSeven`) | 32 | 32 | errors: `PathNotFoundException: $['sourceId']`, because the setup's source upload is refused by the stubbed PDF reader (a wrong outcome of the code under test, not a compile error) |
| RequestContentNotLoggedTest `AtTrace`, `AtConfiguredLevels` | 2 | 2 | errors: same `sourceId` read in the new content sentinel step |

The 7 passing content cases check refusal paths the stubs happen to satisfy (415 for a DOCX or a
renamed PNG, 401/403 without session or CSRF, a missing file part). No compile errors.
