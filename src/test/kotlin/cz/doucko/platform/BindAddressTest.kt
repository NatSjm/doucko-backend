package cz.doucko.platform

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.TimeUnit

/** The stack launcher refuses a bind address outside loopback and RFC 1918 (admin-access "Local network only"). */
class BindAddressTest {
    private fun checkBind(address: String): Pair<Int, String> {
        val process =
            ProcessBuilder("node", "tools/stack.mjs", "--check-bind", address)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "tools/stack.mjs did not finish" }
        return process.exitValue() to output
    }

    // @trace NFR-2
    @ParameterizedTest
    @ValueSource(strings = ["127.0.0.1", "10.0.0.5", "172.16.4.2", "172.31.255.254", "192.168.1.20"])
    fun `loopback and RFC 1918 addresses are accepted`(address: String) {
        checkBind(address).first shouldBe 0
    }

    // @trace NFR-2
    @ParameterizedTest
    @ValueSource(strings = ["0.0.0.0", "::", "8.8.8.8", "172.32.0.1", "192.169.0.1", "192.168.1.300", "localhost", ""])
    fun `wildcard, public and malformed addresses are refused`(address: String) {
        val (exit, output) = checkBind(address)
        exit shouldBe 1
        output shouldContain "DOUCKO_BIND_ADDRESS"
    }
}
