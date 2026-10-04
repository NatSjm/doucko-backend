package cz.doucko.profile.application

import cz.doucko.profile.domain.DisplayNamePolicy
import cz.doucko.profile.domain.FieldError

/** Startup refusal; the message names the variable and the broken rule, never the value (NFR-3). */
class DisplayNameRefusedException(
    val violation: DisplayNamePolicy.Violation,
) : IllegalStateException(DisplayNamePolicy.message(violation))

/** A settings request with invalid fields (400); nothing was changed. */
class InvalidSettingsException(
    val errors: List<FieldError>,
) : RuntimeException(null, null, false, false)

/** `answerMode: GUIDED` in an otherwise valid request (422); nothing was changed. */
class GuidedModeNotAvailableException : RuntimeException(null, null, false, false)
