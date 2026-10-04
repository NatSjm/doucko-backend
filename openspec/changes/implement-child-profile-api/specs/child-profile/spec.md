## ADDED Requirements

### Requirement: Single profile created on first start

The backend SHALL create exactly one child profile on the first start against
an empty database, with the trimmed display name read from
`DOUCKO_CHILD_DISPLAY_NAME` (store spec child-profile, FR-43). On later starts
it SHALL keep the existing profile and SHALL NOT read the variable. The
schema SHALL allow at most one profile row.

#### Scenario: First start stores the trimmed name

- **GIVEN** an empty database and `DOUCKO_CHILD_DISPLAY_NAME` set to `  Hrdinka  `
- **WHEN** the backend starts
- **THEN** exactly one `child_profile` row exists with display name `Hrdinka`, `secondary_task_locale` null and `answer_mode` `DIRECT`

#### Scenario: Restart keeps the profile and its settings

- **GIVEN** a profile created with `Hrdinka` and `secondaryTaskLocale` set to `uk`
- **WHEN** the backend restarts with the variable set to `Jiné jméno`, empty, or 200 characters
- **THEN** it starts, exactly one profile exists, its name is still `Hrdinka` and `GET` settings still returns `uk`

### Requirement: Display name validated on first start

The trimmed display name SHALL be 1 to 50 Unicode code points with no control
character and no U+FFFD. Otherwise the startup SHALL fail before any request
is served, with a message that names `DOUCKO_CHILD_DISPLAY_NAME` and the rule
and never the value, and no profile SHALL be created.

#### Scenario: Invalid names refuse to start

- **GIVEN** an empty database
- **WHEN** the variable is unset, empty, blank, 51 code points after trimming, contains a newline or tab, or contains U+FFFD
- **THEN** the startup fails naming the variable (and the 50-character limit for the long name), no profile exists, and neither the failure nor the log contains the value

#### Scenario: Fifty code points are accepted

- **WHEN** the variable holds 50 code points (including letters outside the BMP)
- **THEN** the backend starts and stores it

### Requirement: Profile settings endpoints

The backend SHALL implement `getProfileSettings` and `updateProfileSettings`
without authentication, admin session or CSRF token (FR-43). `PATCH` SHALL
apply the partial-update rules: an absent field is unchanged, an explicit
`secondaryTaskLocale: null` clears the locale, `{}` changes nothing, and
unknown top-level fields are ignored.

#### Scenario: Defaults, set and clear

- **GIVEN** a new profile
- **WHEN** the client calls `GET`, then `PATCH {"secondaryTaskLocale":"uk"}`, then `PATCH {"secondaryTaskLocale":null}` with no cookie and no CSRF header
- **THEN** the answers are 200 with `null`/`DIRECT`, 200 with `uk`, and 200 with `null`

#### Scenario: Omitted fields and unknown fields

- **GIVEN** `secondaryTaskLocale` is `uk`
- **WHEN** `PATCH` sends `{"answerMode":"DIRECT"}`, `{}` or `{"displayName":"Někdo"}`
- **THEN** each answer is 200 and `secondaryTaskLocale` is still `uk`

### Requirement: Invalid settings refused without side effects

An invalid `PATCH` SHALL be refused with problem+json and SHALL change no
setting. Wrong types, `answerMode: null`, unknown enum values, unsupported or
inexactly matched locales (including `""` and oversized strings) and bodies
that are not a JSON object SHALL be 400 with `errors` naming each invalid
field; the locale error SHALL list the supported locales; no response SHALL
echo the submitted value. `GUIDED` SHALL be 422, checked only after
validation passes. A body over 16 KB SHALL be 413.

#### Scenario: Validation wins over GUIDED

- **WHEN** `PATCH` sends `{"secondaryTaskLocale":"ru","answerMode":"GUIDED"}`
- **THEN** the answer is 400 naming `secondaryTaskLocale` and nothing changes

#### Scenario: GUIDED discards the whole request

- **GIVEN** `secondaryTaskLocale` is `null`
- **WHEN** `PATCH` sends `{"secondaryTaskLocale":"uk","answerMode":"GUIDED"}`
- **THEN** the answer is 422 and `GET` still returns `null` and `DIRECT`

#### Scenario: Oversized locale is not echoed

- **WHEN** `PATCH` sends a 10 240-character `secondaryTaskLocale`
- **THEN** the answer is 400 naming the field, shorter than 2 048 bytes, without the submitted string

### Requirement: Task locales come from configuration

The accepted locales SHALL be read from `doucko.profile.task-locales`
(MVP `uk`), not hardcoded.

#### Scenario: Empty configuration refuses every locale

- **GIVEN** `doucko.profile.task-locales` is empty
- **WHEN** `PATCH` sets `secondaryTaskLocale` to `uk`, then to `null`
- **THEN** the first answer is 400 and the second is 200

### Requirement: Display name and settings stay out of the logs

No log line at any level SHALL contain the display name or a submitted
settings value (NFR-3).

#### Scenario: Log capture over first start and the settings endpoints

- **GIVEN** the display name `Hrdinka`
- **WHEN** the backend starts for the first time, serves `GET`, accepts one `PATCH` and refuses one invalid `PATCH`
- **THEN** no captured log line contains `Hrdinka` or the submitted invalid value
