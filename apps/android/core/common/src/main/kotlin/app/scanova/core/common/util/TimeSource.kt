package app.scanova.core.common.util

import java.time.Instant

/** Injected clock, so time-dependent logic can be tested with fixed instants. */
fun interface TimeSource {
    fun now(): Instant
}
