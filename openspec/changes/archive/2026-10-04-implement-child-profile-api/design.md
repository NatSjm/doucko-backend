# Design — implement-child-profile-api

## Goals / Non-goals

Goals: the store spec `child-profile` behaviors that live in the backend. The
single profile created on first start from `DOUCKO_CHILD_DISPLAY_NAME`, the
display-name validation and startup refusal (message names the variable and
the rule, never the value), `GET`/`PATCH /api/child/profile/settings` with the
partial-update rules (absent vs `null`, `answerMode: null` → 400, `GUIDED` →
422 after validation, atomic refusal, unknown fields ignored), configured task
locales, and the NFR-3 log guarantee for the display name and settings
payloads.

Non-goals and scenarios that need a later slice or another repo:

- The HQ response's `displayName` (scenarios "Profile created from the
  environment on first start", "Unknown fields are ignored") is served by the
  HQ slice (gamification). Here the stored name is checked with a direct
  database query.
- "Restart keeps the profile and its progress": XP and resolved tasks do not
  exist yet (slices 7 and 8). This change proves the profile row and its
  settings survive a restart with a changed variable.
- "Profile count stays 1 after the end-to-end suite", the translation toggle,
  demo recordings and the repository privacy scan belong to the frontend and
  docs repos (practice-flow, NFR-3 tooling). The single-row rule is enforced by
  the schema (`CHECK (id = 1)`).
- "Setting survives a backend restart" is proven by restarting the
  application in-process against the same Postgres database, not by
  restarting Compose containers (the compose smoke covers the containers when
  it can run).
- "Admin API cannot change the profile": the 404s are proven with a signed-in
  admin and CSRF token in a web test; the contract half is
  `ChildOperationsContractTest` plus a new contract assertion.

## Key decisions

1. **Own module `cz.doucko.profile`.** The profile is its own capability
   (child-profile), read later by practice-flow (secondary locale) and the HQ
   (display name). `progress` is the attempt log and stats; mixing the two
   would make the stats module own private data it never needs. The module
   exposes nothing yet; later slices add a public API type when they read it.
2. **Presence-aware body reading.** The generated `ProfileSettingsPatch` turns
   both an absent field and `null` into Kotlin `null`, so it cannot express the
   rules. A `RequestBodyAdvice` limited to that parameter type reads the raw
   body (at most 16 KB, else 413), parses it as a JSON tree and maps each field
   to absent, `null`, a string or a wrong type. The domain validator turns that
   into a `SettingsPatch` or a list of field errors. The advice keeps the parsed
   input as a request attribute for the controller and hands Jackson `{}`, so
   the generated DTO never holds a submitted value. That matters for NFR-3: the
   RED run showed Hibernate Validator tracing the bound DTO (values included)
   at TRACE; `org.hibernate.validator` is now also capped by the log guard.
   Trade-off: the generated DTO parameter is unused. Not ADR-worthy: local to
   this endpoint.
3. **Validation before availability.** Structural and locale errors are
   collected for every field first (400 naming each field). Only a request
   without them is checked for `GUIDED` (422). Nothing is written before both
   checks pass, so a refused request changes no setting.
4. **Configured locales.** `doucko.profile.task-locales` (MVP `uk`), bound with
   `@ConfigurationProperties`. Matching is exact (`UK`, `uk-UA`, ` uk` are
   refused). An empty list refuses every locale while `null` still succeeds.
5. **Display name read only on first start.** The initializer runs while the
   context refreshes (before the web server takes requests). It reads the
   variable through the `Environment` only when no profile row exists, so a
   restart with the variable unset or invalid starts normally. A refused value
   throws, which fails `SpringApplication.run` and exits the process non-zero.
6. **Display name policy.** Trim surrounding whitespace, then 1–50 Unicode code
   points, no control characters (category Cc) and no U+FFFD. Invalid UTF-8 in
   the environment is decoded by the JVM into U+FFFD, so that rule covers the
   `0xC3 0x28` scenario. Messages name `DOUCKO_CHILD_DISPLAY_NAME` and the rule.
7. **Errors never echo input.** Field messages and details come from
   `messages.properties` (NFR-9) and contain no submitted value; the generated
   enum's `IllegalArgumentException` (which quotes the value) is never reached
   because the tree is validated first.

## Module and hexagon placement

- `profile/domain/`: `AnswerMode`, `ProfileSettings`, `DisplayNamePolicy`,
  `SettingsInput` / `SettingsPatch` / `SettingsValidator` (pure Kotlin).
- `profile/application/`: `ChildProfileStore` (port), `ProfileSettingsService`,
  `ChildProfileInitializer`, `TaskLocaleProperties`, `ProfileConfiguration`,
  refusal exceptions.
- `profile/adapter/in/web/`: `ProfileSettingsController` (implements
  `ChildProfileApi`), `SettingsBodyAdvice`, `ProfileProblemAdvice`.
- `profile/adapter/out/persistence/`: `ChildProfileEntity`,
  `ChildProfileJpaRepository`, `JpaChildProfileStore`.

## Data model

`V1__child_profile.sql` (the first migration, append-only from here):

| Column | Type | Rule |
|---|---|---|
| `id` | `integer` PK | `CHECK (id = 1)`: at most one profile |
| `display_name` | `varchar(50)` not null | non-blank |
| `secondary_task_locale` | `varchar(16)` null | null = translation off |
| `answer_mode` | `varchar(16)` not null default `DIRECT` | `DIRECT` or `GUIDED` |
| `created_at` | `timestamptz` not null default `now()` | |

No data statement: the row is created by the application on first start.
Updates lock the row (`SELECT … FOR UPDATE`) inside one transaction.

## Error handling

| Case | Status | Title key |
|---|---|---|
| Wrong type, `null` answer mode, unknown enum value, unsupported locale | 400, `errors[]` per field; locale detail lists the supported locales | `profile-settings-invalid` |
| Body not JSON, not an object, or empty | 400 | `profile-settings-unreadable` |
| `GUIDED` after validation passed | 422 | `guided-not-available` |
| Body over 16 KB | 413 | `profile-settings-too-large` |

## Risks & mitigations

- The display name in logs: nothing in the module logs it; the entity has no
  data-class `toString`; Hibernate bind loggers are capped by the slice 1 log
  guard. The log-capture tests cover first start, `GET`, a valid and an invalid
  `PATCH`.
- The variable is optional in Compose (`${DOUCKO_CHILD_DISPLAY_NAME:-}`): later
  starts must not need it (spec). A first start without it refuses with a
  message naming the variable.
