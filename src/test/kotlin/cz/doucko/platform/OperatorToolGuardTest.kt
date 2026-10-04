package cz.doucko.platform

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * NFR-10 / NFR-3: the backup and restore tools refuse unsafe targets before they touch Docker.
 * A backup holds the child's data, so it never lands in a git working tree, even through a link,
 * and on Windows only under the user's profile (owner-only ACL).
 */
class OperatorToolGuardTest {
    @TempDir
    lateinit var tmp: File

    private val windows = System.getProperty("os.name").startsWith("Windows")

    private fun node(vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder("node", *args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "node ${args.joinToString(" ")} did not finish" }
        return process.exitValue() to output
    }

    // @trace NFR-10
    @Test
    fun `a backup directory inside a git working tree is refused`() {
        val inside = File("target/backup-probe-${System.nanoTime()}")
        val (exit, output) = node("tools/backup.mjs", "--out", inside.path)
        exit shouldBe 1
        output shouldContain "inside the git working tree"
        inside.exists() shouldBe false
    }

    // @trace NFR-10
    @Test
    fun `a link that leads into a git working tree is refused`() {
        val target = File("target").absoluteFile.apply { mkdirs() }
        val link = File(tmp, "looks-outside")
        if (windows) {
            ProcessBuilder("cmd", "/c", "mklink", "/J", link.path, target.path).start().waitFor(30, TimeUnit.SECONDS)
        } else {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        }
        assumeTrue(link.exists(), "could not create a link on this machine")
        val (exit, output) = node("tools/backup.mjs", "--out", File(link, "dumps").path)
        exit shouldBe 1
        output shouldContain "inside the git working tree"
    }

    // @trace NFR-10
    @Test
    fun `on Windows a backup directory outside the user profile is refused`() {
        assumeTrue(windows, "Windows-only: elsewhere the 0700/0600 modes make the dump owner-only")
        val outside =
            File(
                File(System.getProperty("user.home"))
                    .absoluteFile
                    .toPath()
                    .root
                    .toFile(),
                "doucko-backup-probe-${System.nanoTime()}",
            )
        val (exit, output) = node("tools/backup.mjs", "--out", outside.path)
        exit shouldBe 1
        output shouldContain "user profile"
        outside.exists() shouldBe false
    }

    // @trace NFR-10
    @Test
    fun `restore given a directory prints its usage, not a stack trace`() {
        val (exit, output) = node("tools/restore.mjs", "--yes", tmp.path)
        exit shouldBe 2
        output shouldContain "usage:"
        output shouldNotContain "    at "
    }
}
