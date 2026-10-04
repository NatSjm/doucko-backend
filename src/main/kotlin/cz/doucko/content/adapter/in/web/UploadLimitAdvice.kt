package cz.doucko.content.adapter.`in`.web

import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException

/**
 * NFR-17: a multipart body over `spring.servlet.multipart.max-file-size` (50 MB) is refused while the
 * request is parsed, before any handler is chosen, so only an advice without selectors sees it. It
 * answers 413 problem+json naming the allowed type and size (never Spring's generic text).
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class UploadLimitAdvice(
    private val problems: ContentProblems,
) {
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun tooLarge(request: HttpServletRequest) = problems.uploadLimit(request)
}
