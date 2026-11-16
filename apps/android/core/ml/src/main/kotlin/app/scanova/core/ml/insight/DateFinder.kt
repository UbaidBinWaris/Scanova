package app.scanova.core.ml.insight

import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A date written on a document. */
internal data class FoundDate(
    /** The day, or null when only a month is written ("Billing month: Oct 2026"). */
    val date: LocalDate?,
    val month: YearMonth,
    /** Zero-based line of the text it was found on. */
    val line: Int,
    /** What the words around it say it is. */
    val role: DateRole,
) {
    /** "16 Nov 2026", or "Oct 2026" for a month (or always, with [monthOnly]). */
    fun format(monthOnly: Boolean = false): String = if (monthOnly || date == null) month.format(MONTH) else date.format(DAY)

    private companion object {
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
        val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)
    }
}

internal enum class DateRole {
    /** Labelled as the document's own date: "Date", "Invoice date", "Dated", "Issued on"… */
    DOCUMENT,

    /** Labelled as a travel date: "Departure", "Date of travel"… */
    TRAVEL,

    /** Labelled as the period a bill or statement covers: "Billing month", "Statement period"… */
    PERIOD,

    /** Not the document's date: due, expiry, birth, validity. Never used in a title. */
    OTHER_DATE,

    /** No label. */
    PLAIN,
}

/**
 * Finds dates in recognised text: 16/11/2026, 16-11-26, 2026-11-16,
 * 16 Nov 2026, 16th November, 2026, Nov 16, 2026, and months such as
 * "Oct 2026". Day-first unless the numbers say otherwise (13/05 vs 05/13),
 * as in Pakistan and the UK. OCR mix-ups next to digits (O for 0, l or I for 1)
 * are corrected first.
 */
internal object DateFinder {
    private const val MONTH_NAMES =
        "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"

    private val NUMERIC = Regex("(?<![\\d.])(\\d{1,4})\\s?([./-])\\s?(\\d{1,2})\\s?\\2\\s?(\\d{2,4})(?![\\d])")
    private val DAY_MONTH_YEAR = Regex(
        "(?<!\\d)(\\d{1,2})(?:st|nd|rd|th)?[\\s./,-]*($MONTH_NAMES)\\.?[\\s./,'-]*(\\d{4}|\\d{2})(?!\\d)",
        RegexOption.IGNORE_CASE,
    )
    private val MONTH_DAY_YEAR = Regex(
        "(?<![a-z])($MONTH_NAMES)\\.?[\\s.-]*(\\d{1,2})(?:st|nd|rd|th)?[,\\s.-]+(\\d{4})(?!\\d)",
        RegexOption.IGNORE_CASE,
    )
    private val MONTH_YEAR = Regex(
        "(?<![a-z\\d])($MONTH_NAMES)\\.?[\\s,'-]*(\\d{4}|\\d{2})(?![\\d:])",
        RegexOption.IGNORE_CASE,
    )

    private val OTHER_LABELS = listOf(
        "due", "expiry", "expires", "expire", "exp", "valid", "validity", "birth", "dob", "born", "until", "upto",
        "last date", "pay by", "payable by", "renewal", "maturity",
    )
    private val TRAVEL_LABELS = listOf("departure", "depart", "departs", "travel", "journey", "flight date", "show date", "event date", "boarding")
    private val PERIOD_LABELS = listOf("billing month", "bill month", "billing period", "statement period", "period", "month", "for the month", "from")
    private val DOCUMENT_LABELS = listOf(
        "date", "dated", "issued", "issue", "invoice date", "bill date", "receipt date", "statement date", "order date",
        "printed",
    )

    fun find(text: String): List<FoundDate> {
        val lines = text.lines()
        val found = ArrayList<FoundDate>()
        lines.forEachIndexed { index, raw ->
            val line = fixDigits(raw)
            val taken = ArrayList<IntRange>()
            fun add(range: IntRange, date: LocalDate?, month: YearMonth?) {
                if (month == null || taken.any { it.first <= range.last && range.first <= it.last }) return
                taken += range
                found += FoundDate(date, month, index, roleOf(line, range.first, lines.getOrNull(index - 1)))
            }
            for (match in NUMERIC.findAll(line)) {
                val date = numericDate(match.groupValues[1], match.groupValues[3], match.groupValues[4])
                add(match.range, date, date?.let(YearMonth::from))
            }
            for (match in DAY_MONTH_YEAR.findAll(line)) {
                val date = date(year(match.groupValues[3]), month(match.groupValues[2]), match.groupValues[1].toInt())
                add(match.range, date, date?.let(YearMonth::from))
            }
            for (match in MONTH_DAY_YEAR.findAll(line)) {
                val date = date(year(match.groupValues[3]), month(match.groupValues[1]), match.groupValues[2].toInt())
                add(match.range, date, date?.let(YearMonth::from))
            }
            for (match in MONTH_YEAR.findAll(line)) {
                val year = year(match.groupValues[2])
                val month = month(match.groupValues[1])
                val yearMonth = if (year != null && month != null) YearMonth.of(year, month) else null
                add(match.range, null, yearMonth)
            }
        }
        return found.sortedWith(compareBy({ it.line }))
    }

    /**
     * The date that best says when the document is from: a labelled one of
     * the [preferred] roles first, then any labelled document date, then the
     * first unlabelled one; a month only when no day is written. Due, expiry
     * and birth dates are never chosen.
     */
    fun pick(text: String, preferred: List<DateRole> = listOf(DateRole.DOCUMENT)): FoundDate? {
        val dates = find(text).filter { it.role != DateRole.OTHER_DATE }
        for (role in preferred) {
            val labelled = dates.filter { it.role == role }
            (labelled.firstOrNull { it.date != null } ?: labelled.firstOrNull())?.let { return it }
        }
        val days = dates.filter { it.date != null }
        days.firstOrNull { it.role == DateRole.DOCUMENT }?.let { return it }
        days.firstOrNull { it.role == DateRole.PLAIN }?.let { return it }
        return days.firstOrNull() ?: dates.firstOrNull()
    }

    private fun roleOf(line: String, at: Int, previous: String?): DateRole {
        val before = line.substring(0, at)
        // The label is usually just before the date, on the same line or the line above.
        val context = before.takeLast(LABEL_REACH).ifBlank { previous?.takeLast(LABEL_REACH).orEmpty() }
        val words = MatchText.of(context)
        return when {
            FOLDED_OTHER.any(words::contains) -> DateRole.OTHER_DATE
            FOLDED_TRAVEL.any(words::contains) -> DateRole.TRAVEL
            FOLDED_PERIOD.any(words::contains) -> DateRole.PERIOD
            FOLDED_DOCUMENT.any(words::contains) -> DateRole.DOCUMENT
            else -> DateRole.PLAIN
        }
    }

    private val FOLDED_OTHER = OTHER_LABELS.map(MatchText::fold)
    private val FOLDED_TRAVEL = TRAVEL_LABELS.map(MatchText::fold)
    private val FOLDED_PERIOD = PERIOD_LABELS.map(MatchText::fold)
    private val FOLDED_DOCUMENT = DOCUMENT_LABELS.map(MatchText::fold)

    private fun numericDate(first: String, second: String, third: String): LocalDate? {
        val a = first.toInt()
        val b = second.toInt()
        if (first.length == 4) {
            if (a !in MIN_YEAR..MAX_YEAR || third.length > 2) return null
            return date(a, b, third.toInt())
        }
        if (first.length > 2) return null
        val year = year(third) ?: return null
        return when {
            a > 12 && b <= 12 -> date(year, b, a)
            b > 12 && a <= 12 -> date(year, a, b)
            else -> date(year, b, a)
        }
    }

    private fun year(text: String): Int? {
        val value = text.toIntOrNull() ?: return null
        val year = when (text.length) {
            2 -> if (value <= 69) 2000 + value else 1900 + value
            4 -> value
            else -> return null
        }
        return year.takeIf { it in MIN_YEAR..MAX_YEAR }
    }

    private fun month(name: String): Int? = when (name.lowercase().take(3)) {
        "jan" -> 1
        "feb" -> 2
        "mar" -> 3
        "apr" -> 4
        "may" -> 5
        "jun" -> 6
        "jul" -> 7
        "aug" -> 8
        "sep" -> 9
        "oct" -> 10
        "nov" -> 11
        "dec" -> 12
        else -> null
    }

    private fun date(year: Int?, month: Int?, day: Int): LocalDate? {
        if (year == null || month == null) return null
        return try {
            LocalDate.of(year, month, day)
        } catch (e: DateTimeException) {
            null
        }
    }

    /** O, o, l, I and | next to a digit are digits that OCR misread: "1O/1l/2026" → "10/11/2026". */
    private fun fixDigits(line: String): String {
        if (line.none(Char::isDigit)) return line
        val chars = line.toCharArray()
        for (i in chars.indices) {
            val replacement = when (chars[i]) {
                'O', 'o' -> '0'
                'l', 'I', '|' -> '1'
                else -> continue
            }
            val left = chars.getOrNull(i - 1)
            val right = chars.getOrNull(i + 1)
            val digitBeside = left?.isDigit() == true || right?.isDigit() == true
            val otherSideOk = { c: Char? -> c == null || c.isDigit() || c in "/.-" || c.isWhitespace() }
            if (digitBeside && otherSideOk(left) && otherSideOk(right)) chars[i] = replacement
        }
        return String(chars)
    }

    private const val LABEL_REACH = 28
    private const val MIN_YEAR = 1950
    private const val MAX_YEAR = 2100
}
