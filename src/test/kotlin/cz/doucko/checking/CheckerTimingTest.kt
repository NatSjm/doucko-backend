package cz.doucko.checking

import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * answer-checking "Checker share of the round-trip budget" (NFR-5, derived): every golden case checked 100
 * times, each check timed, p95 below 5 ms, far under the 300 ms answer-check round trip.
 */
class CheckerTimingTest {
    // @trace FR-23
    // @trace NFR-5
    @Test
    fun `p95 of one part check is below 5 ms`() {
        val cases = GoldenTable.cases
        repeat(20) { cases.forEach { AnswerChecker.check(it.spec, it.input) } }
        val samples = LongArray(cases.size * 100)
        var i = 0
        var mismatches = 0
        repeat(100) {
            for (case in cases) {
                val start = System.nanoTime()
                val result = AnswerChecker.check(case.spec, case.input)
                samples[i++] = System.nanoTime() - start
                if (result.verdict != case.verdict) mismatches++
            }
        }
        samples.sort()
        val p95 = samples[(samples.size * 95 + 99) / 100 - 1]
        val max = samples.last()
        println("CheckerTimingTest: ${samples.size} checks, p95 = ${p95 / 1_000} us, max = ${max / 1_000} us")
        mismatches shouldBe 0
        p95 shouldBeLessThan 5_000_000L
    }
}
