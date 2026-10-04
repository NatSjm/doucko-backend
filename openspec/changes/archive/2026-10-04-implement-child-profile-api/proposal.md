# Implement child profile (backend)

## Why

Slice 4 of the capability plan (`contract/docs/mvp-capability-plan.md` §4.4).
Store spec: `child-profile`. MVP has one learner and no child login: the
backend creates a single profile on first start from the display-name
environment variable and stores the two practice settings the later slices
need (FR-43). It travels NFR-3 for the display name: the name and the settings
payloads never reach a log line.

## What Changes

- Implements `ChildProfileApi` (`getProfileSettings`, `updateProfileSettings`)
  from the pinned contract, login-free under the shared stateless chain.
- New module `cz.doucko.profile`: display-name policy and settings-patch
  validation (domain), the profile store port, the settings use case and the
  first-start initializer (application), the web adapter with a
  presence-aware body reader, and a JPA persistence adapter.
- First Flyway migration `V1__child_profile.sql`: table `child_profile`, at
  most one row (`id = 1`).
- Configuration: `DOUCKO_CHILD_DISPLAY_NAME` (read only on first start, never
  committed) in `.env.example` and the Compose backend service; supported task
  locales in `doucko.profile.task-locales` (MVP `uk`).
- NFR-3 log-capture test extended with the settings endpoints and the
  first-start display name.

## Impact

- Store spec: child-profile · Contract pin: docs `4c87d67` (API 0.5.1), no contract change
- Modules: `cz.doucko.profile` (new) · Flyway: `V1__child_profile.sql`
- Dependencies: slice 1 (and slice 2's chain layout); no new Maven dependencies
- Operators must set `DOUCKO_CHILD_DISPLAY_NAME` in `.env` before the first
  start against an empty database, or the backend refuses to start.
