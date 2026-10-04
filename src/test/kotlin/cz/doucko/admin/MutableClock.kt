package cz.doucko.admin

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A backend clock the idle-timeout tests move forward by hand. */
class MutableClock(
    private var now: Instant = Instant.parse("2026-10-05T08:00:00Z"),
) : Clock() {
    fun advance(by: Duration) {
        now = now.plus(by)
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}
