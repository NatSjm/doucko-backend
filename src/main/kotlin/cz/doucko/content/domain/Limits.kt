package cz.doucko.content.domain

import java.text.Normalizer
import java.util.Locale

/** content-authoring "Field limits" (CA-D15) and NFR-17 upload limits. Text limits are Unicode code points after trimming. */
object Limits {
    const val TITLE = 200
    const val PUBLISHER = 200
    const val EDITION = 50
    const val PROMPT = 4000
    const val HINT = 1000
    const val TASK_NO = 20
    const val LABEL = 10
    const val SUB_PART = 10
    const val OPTION_TEXT = 200
    const val TEXT_ANSWER = 100
    const val QUERY = 200
    const val IMAGES = 10
    const val HINTS = 10
    const val PARTS = 10
    const val ACCEPTED = 10
    const val OPTIONS_MIN = 2
    const val OPTIONS_MAX = 10
    const val DIFFICULTY_MAX = 3
    const val BULK_PUBLISH = 100
    const val MISSION_TASKS = 50
    const val POSITION_MAX = 999
    const val DECIMALS = 6
    const val PDF_BYTES = 50L * 1024 * 1024
    const val IMAGE_BYTES = 5L * 1024 * 1024
}

/** Text helpers of the field-limit rules and the task search (CA-D19). */
object Text {
    /** Trims leading and trailing whitespace, including no-break and thin spaces. */
    fun trim(value: String): String = value.trim { it.isWhitespace() || it == ' ' || it == ' ' || it == ' ' }

    fun codePoints(value: String): Int = value.codePointCount(0, value.length)

    private val MARKS = Regex("\\p{M}+")

    /** Case- and diacritics-insensitive comparison form: NFD, combining marks removed, lower case. */
    fun fold(value: String): String = MARKS.replace(Normalizer.normalize(value, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
}

/** The kind of an uploaded file, decided by its first bytes, never by its name (CA-D1). */
enum class FileKind(
    val mediaType: String,
    val extension: String,
) {
    PDF("application/pdf", "pdf"),
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    OTHER("application/octet-stream", "bin"),
    ;

    companion object {
        private val PDF_SIGNATURE = "%PDF-".toByteArray(Charsets.US_ASCII)
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

        fun detect(head: ByteArray): FileKind =
            when {
                head.startsWith(PDF_SIGNATURE) -> PDF
                head.startsWith(PNG_SIGNATURE) -> PNG
                head.startsWith(JPEG_SIGNATURE) -> JPEG
                else -> OTHER
            }

        private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    }
}
