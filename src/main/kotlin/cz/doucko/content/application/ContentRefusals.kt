package cz.doucko.content.application

import cz.doucko.content.domain.FieldError
import java.util.UUID

/** Refusals of the content use cases; the web adapter turns each into problem+json. */
sealed class ContentRefusal : RuntimeException(null, null, false, false)

/** 400: one error per invalid field (contract field paths). */
class InvalidContent(
    val errors: List<FieldError>,
) : ContentRefusal()

/** 404: no such source, task or image. */
class ContentNotFound : ContentRefusal()

/** 409: refused by a state rule; [code] is the catalog key of the detail, [conflicts] the blocking ids. */
class ContentConflict(
    val code: String,
    val args: List<Any>,
    val conflicts: List<UUID>,
) : ContentRefusal()

enum class UploadKind { PDF, IMAGE }

/** 415: the content is not an allowed type (CA-D1). */
class UnsupportedUpload(
    val kind: UploadKind,
) : ContentRefusal()

/** 413: over the NFR-17 size limit. */
class UploadTooLarge(
    val kind: UploadKind,
) : ContentRefusal()
