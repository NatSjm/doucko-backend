package cz.doucko

import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every Kotlin source declares the package its directory names (with `in` written as a backticked
 * keyword). This replaces ktlint's package-name rule, which is off under `adapter/in/` (.editorconfig).
 */
class PackageLayoutTest {
    // @trace NFR-11
    @Test
    fun `each source file's package matches its directory`() {
        val root = File("src/main/kotlin")
        val mismatches =
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .mapNotNull { file ->
                    val expected =
                        file.parentFile
                            .relativeTo(root)
                            .invariantSeparatorsPath
                            .split('/')
                            .joinToString(".") { if (it == "in") "`in`" else it }
                    val declared =
                        file.useLines { lines ->
                            lines.firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim()
                        }
                    if (declared == expected &&
                        expected.split('.').all { Regex("""`in`|[a-z][a-z0-9]*""").matches(it) }
                    ) {
                        null
                    } else {
                        "$file: $declared"
                    }
                }.toList()
        mismatches.shouldBeEmpty()
    }
}
