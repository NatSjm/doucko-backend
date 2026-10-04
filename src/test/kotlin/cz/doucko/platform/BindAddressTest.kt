package cz.doucko.platform

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.util.concurrent.TimeUnit

/** The stack launcher refuses a bind address outside loopback and RFC 1918 (admin-access "Local network only"). */
class BindAddressTest {
    @TempDir
    lateinit var tmp: File

    private fun stack(vararg args: String): Pair<Int, String> {
        val builder = ProcessBuilder("node", "tools/stack.mjs", *args).redirectErrorStream(true)
        // Compose lets the process environment win over the env file; keep the test's input the file alone.
        builder.environment().keys.removeAll { it.startsWith("DOUCKO_") || it == "ADMIN_PASSWORD" }
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "tools/stack.mjs did not finish" }
        return process.exitValue() to output
    }

    private fun envFile(text: String) = File(tmp, "test.env").apply { writeText(text) }.path

    /** The variables compose.yaml requires (`${...:?}`); dummy values, compose only interpolates them here. */
    private val requiredEnv = "DOUCKO_DB_PASSWORD=x\nADMIN_PASSWORD=x\n"

    // @trace NFR-2
    @ParameterizedTest
    @ValueSource(strings = ["127.0.0.1", "10.0.0.5", "172.16.4.2", "172.31.255.254", "192.168.1.20"])
    fun `loopback and RFC 1918 addresses are accepted`(address: String) {
        stack("--check-bind", address).first shouldBe 0
    }

    // @trace NFR-2
    @ParameterizedTest
    @ValueSource(
        strings = [
            "0.0.0.0", "::", "8.8.8.8", "172.32.0.1", "192.169.0.1", "192.168.1.300", "localhost", "",
            "010.0.0.5", "127.000.000.001", "::ffff:192.168.1.20", "0", " 127.0.0.1",
        ],
    )
    fun `wildcard, public and malformed addresses are refused`(address: String) {
        val (exit, output) = stack("--check-bind", address)
        exit shouldBe 1
        output shouldContain "DOUCKO_BIND_ADDRESS"
    }

    // @trace NFR-2
    @Test
    fun `the launcher checks the binding compose will use, not its own reading of the env file`() {
        // compose-go accepts `export` and lets a later duplicate win: the effective address is 0.0.0.0.
        val file = envFile("DOUCKO_BIND_ADDRESS=127.0.0.1\nexport DOUCKO_BIND_ADDRESS=0.0.0.0\n$requiredEnv")
        val (exit, output) = stack("--check-config", "--env-file", file)
        exit shouldBe 1
        output shouldContain "0.0.0.0"
    }

    // @trace NFR-2
    @Test
    fun `the default compose binding passes the launcher check`() {
        stack("--check-config", "--env-file", envFile(requiredEnv)).first shouldBe 0
    }

    // @trace NFR-2
    @Test
    fun `up refuses --env-file, so the configuration it checks is the one it starts`() {
        val (exit, output) = stack("up", "--env-file", envFile(requiredEnv))
        exit shouldBe 2
        output shouldContain "--env-file"
    }
}
