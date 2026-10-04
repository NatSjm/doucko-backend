package cz.doucko.platform

import io.kotest.matchers.collections.shouldContainAll
import org.junit.jupiter.api.Test
import java.io.File

/** NFR-3 / NFR-4: secrets, dumps and fixtures never enter the backend image's build context. */
class DockerContextTest {
    // @trace NFR-3
    @Test
    fun `dockerignore excludes env files, dumps and fixtures at any depth`() {
        val patterns = File(".dockerignore").readLines().map(String::trim)
        patterns shouldContainAll listOf("**/.env", "**/.env.*", "**/*.dump", "fixtures/")
    }
}
