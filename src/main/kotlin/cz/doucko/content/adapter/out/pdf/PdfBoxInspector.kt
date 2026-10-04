package cz.doucko.content.adapter.out.pdf

import cz.doucko.content.application.PdfInspector
import org.apache.pdfbox.Loader
import org.springframework.stereotype.Component

/**
 * Reads a PDF's page count with PDFBox (FR-1) and refuses what cannot be read reliably (CA-D2): an
 * encrypted file (with or without a user password), a file without `%%EOF` near its end (truncated;
 * PDFBox would otherwise repair it silently), an unparseable file, and a page tree with no page.
 */
@Component
class PdfBoxInspector : PdfInspector {
    override fun pageCount(content: ByteArray): Int? {
        if (!hasEndMarker(content)) return null
        return try {
            Loader.loadPDF(content).use { document ->
                if (document.isEncrypted) null else document.numberOfPages.takeIf { it > 0 }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun hasEndMarker(content: ByteArray): Boolean {
        val tail = String(content, maxOf(0, content.size - TAIL), minOf(content.size, TAIL), Charsets.ISO_8859_1)
        return tail.contains("%%EOF")
    }

    private companion object {
        /** The end marker must lie within the last KB (PDF 1.7, 7.5.5: the last line; readers allow some slack). */
        const val TAIL = 1024
    }
}
