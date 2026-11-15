package app.scanova.core.data.util

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Parses an ISO-8601 time from the API. Returns null instead of throwing on bad input. */
fun parseInstantOrNull(text: String?): Instant? {
    if (text.isNullOrBlank()) return null
    return try {
        Instant.parse(text)
    } catch (e: DateTimeParseException) {
        try {
            OffsetDateTime.parse(text).toInstant()
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
