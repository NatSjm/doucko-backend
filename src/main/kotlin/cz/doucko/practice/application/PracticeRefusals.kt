package cz.doucko.practice.application

import cz.doucko.practice.domain.FieldError

/** The mission, task or image is unknown or not served to the child (404, FR-12, FR-26). */
class PracticeNotFound : RuntimeException(null, null, false, false)

/** A state rule refuses the request (409): [code] picks the detail `practice.conflict.<code>`. */
class PracticeConflict(
    val code: String,
) : RuntimeException(null, null, false, false)

/** Invalid request fields (400), never quoting a submitted value (NFR-3). */
class InvalidPracticeInput(
    val errors: List<FieldError>,
) : RuntimeException(null, null, false, false)
