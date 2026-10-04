# Slice 2 (implement-admin-access-api) — RED run, task 2.6

- **Date:** 2026-10-04 (Europe/Prague)
- **Command:** `node scripts/mvnw.mjs -B -q test -Dtest='Admin*Test,UndeclaredPathTest'`
- **Against:** compile-only stubs (`AdminPasswordPolicy.violation` returns null,
  `AdminCredentials.matches` returns false, empty `AdminSecurityConfiguration`,
  no controller) and the slice 1 security chain (`/api/admin/**` denyAll, 403).

| Test class | Run | Failed | Why |
|---|---|---|---|
| AdminPasswordPolicyTest | 6 | 6 | assertions: no violation reported, empty messages |
| AdminStartupTest | 8 | 8 | assertions: bad passwords start; good ones do not sign in |
| AdminCredentialsTest | 3 | 2 | assertions: no match, empty hash (the "never signs in" case passes against a stub that always refuses) |
| AdminSessionTest | 34 | 34 | assertions: 403 instead of 401, no sign-in handler |
| AdminIdleTimeoutTest | 5 | 5 | assertions: sign-in is not 204 |
| UndeclaredPathTest | 8 | 4 errors | `problem.title.401` is not in the catalog yet (the admin case now expects 401) |

No compile errors. `AdminSessionIT` (Docker) was not part of this run. It
runs in `mvnw verify` after the implementation.
