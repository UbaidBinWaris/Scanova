package app.scanova.core.ml.insight

/**
 * Builds a title from a document's type and text: the type, the detail that
 * tells documents of that type apart, and the date written on it —
 * "Receipt – Imtiaz Store – 16 Nov 2026", "Bill – K-Electric – Oct 2026",
 * "ID card – Ayesha Khan", "Certificate of Achievement – Sara Ahmed". Parts
 * that cannot be found are left out; a document of no known type gets its
 * heading.
 */
internal object TitleBuilder {
    private const val SEPARATOR = " – "
    private const val MAX_TITLE = 80
    private const val MAX_PART = 48
    private const val TOP_LINES = 8

    fun build(type: DocumentType, text: String): String? {
        val lines = Headings.lines(text)
        if (lines.isEmpty()) return null
        val title = when (type) {
            DocumentType.RECEIPT -> typed(type, issuer(lines, type), DateFinder.pick(text))
            DocumentType.INVOICE -> typed(type, issuer(lines, type), DateFinder.pick(text))
            DocumentType.BILL -> typed(type, issuer(lines, type), DateFinder.pick(text, listOf(DateRole.PERIOD, DateRole.DOCUMENT)), monthOnly = true)
            DocumentType.BANK_STATEMENT -> typed(type, bank(lines), DateFinder.pick(text, listOf(DateRole.PERIOD, DateRole.DOCUMENT)), monthOnly = true)
            DocumentType.PRESCRIPTION -> typed(type, prescriber(lines), DateFinder.pick(text))
            DocumentType.TICKET -> typed(type, carrier(lines), DateFinder.pick(text, listOf(DateRole.TRAVEL, DateRole.DOCUMENT)))
            DocumentType.LETTER -> letter(lines, text)
            DocumentType.ID_CARD -> idCard(lines, text)
            DocumentType.CERTIFICATE -> certificate(lines)
            DocumentType.FORM -> form(lines)
            // A subject line names a document best; then its heading.
            DocumentType.OTHER -> (subject(lines) ?: Headings.heading(text))?.let { Headings.shorten(it, MAX_TITLE_PLAIN) }
        }
        return title?.let { Headings.shorten(it, MAX_TITLE) }
    }

    /** A bill or statement covers a month: its period is written as the month. */
    private fun typed(type: DocumentType, detail: String?, date: FoundDate?, monthOnly: Boolean = false): String {
        val dated = date?.format(monthOnly = monthOnly && date.role == DateRole.PERIOD)
        return join(type.tag, detail, dated)
    }

    private fun join(vararg parts: String?): String =
        parts.filterNotNull().map { Headings.shorten(it, MAX_PART) }.filter { it.isNotBlank() }.joinToString(SEPARATOR)

    // ── Who issued it ───────────────────────────────────────────────────

    /** Words that make a line a label, an address or boilerplate rather than a name. */
    private val NOT_A_NAME = listOf(
        "tel", "phone", "ph", "fax", "mob", "mobile", "cell", "whatsapp", "www", "http", "https", "com", "email", "e mail",
        "ntn", "strn", "gst", "vat", "reg", "registration", "address", "road", "rd", "street", "st", "block", "plot",
        "sector", "floor", "near", "opp", "opposite", "phase", "colony",
        "welcome", "thank", "thanks", "receipt", "invoice", "tax", "bill", "statement",
        "cash memo", "memo", "original", "duplicate", "copy", "customer copy", "page", "date", "time", "no", "number",
        "ticket", "boarding pass", "e ticket", "eticket", "prescription", "rx", "from", "dear", "subject",
        "account", "customer", "cashier", "terminal", "order", "table", "sales", "proforma", "utility", "consumer",
        "reference", "billing", "month", "due", "pos", "fbr", "branch", "iban", "period", "name", "id", "passenger",
        "patient", "booking", "pnr", "seat", "flight", "gate", "class",
    ).map(MatchText::fold)

    private val FROM = Regex("^(?:bill(?:ed)?\\s+)?from\\s*[:\\-]\\s*(.+)$", RegexOption.IGNORE_CASE)

    /** Lines after which the text is about the customer, not the issuer. */
    private val CUSTOMER_START = listOf("bill to", "billed to", "invoice to", "ship to", "sold to", "dear").map(MatchText::fold)

    /** The first line near the top that names a shop, company or utility. */
    private fun issuer(lines: List<String>, type: DocumentType): String? {
        val top = lines.take(TOP_LINES)
        for (line in top) {
            // "From: Sara Malik Design Studio"
            FROM.find(line)?.groupValues?.get(1)?.let { value -> if (isName(value)) return displayName(value) }
            val words = MatchText.of(line)
            if (CUSTOMER_START.any(words::contains)) break
            if (isName(line, words)) return displayName(line)
        }
        // A utility or company name often sits in the middle of a heading line ("K-ELECTRIC BILL").
        if (type == DocumentType.BILL) {
            for (line in top) {
                val known = KNOWN_UTILITIES.firstOrNull { MatchText.of(line).contains(MatchText.fold(it)) }
                if (known != null) return known
            }
        }
        return null
    }

    private fun isName(line: String, words: MatchText = MatchText.of(line)): Boolean {
        val cleaned = cleanName(line)
        if (cleaned.length < 3 || cleaned.length > MAX_NAME) return false
        if (!Headings.looksLikeTitle(cleaned) || !Headings.readsAsWords(cleaned)) return false
        val digits = cleaned.count(Char::isDigit)
        if (digits * 4 > cleaned.length) return false
        return NOT_A_NAME.none(words::contains)
    }

    /** "IMTIAZ STORE (PVT) LTD." keeps its words; leading and trailing decoration goes. */
    private fun cleanName(line: String): String = line.trim().trim { it in " -–—:.,;|_*#=~•·>" }.trim()

    /** A name as it goes into a title: tidied, keeping the dot of a closing "Inc." or "Ltd.". */
    private fun displayName(line: String): String {
        val name = Headings.tidy(cleanName(line))
        val abbreviation = line.trim().endsWith(".") && ABBREVIATIONS.any { name.endsWith(it, ignoreCase = true) }
        return if (abbreviation) "$name." else name
    }

    private val ABBREVIATIONS = listOf(" Inc", " Ltd", " Co", " Corp", ",Inc", ",Ltd")

    private val KNOWN_UTILITIES = listOf(
        "K-Electric", "LESCO", "IESCO", "MEPCO", "FESCO", "GEPCO", "PESCO", "HESCO", "QESCO", "SNGPL", "SSGC", "PTCL",
        "WAPDA", "Nayatel", "StormFiber",
    )

    private fun bank(lines: List<String>): String? {
        val top = lines.take(TOP_LINES + 4)
        top.firstOrNull { line ->
            val words = MatchText.of(line)
            words.contains("bank") && !words.contains(MatchText.fold("bank statement")) && cleanName(line).length <= MAX_NAME &&
                Headings.readsAsWords(line) && line.count(Char::isDigit) * 4 <= line.length
        }?.let { return displayName(it) }
        return issuer(lines, DocumentType.BANK_STATEMENT)
    }

    private val DOCTOR = Regex("^(?:dr|dr\\.|doctor)\\s+\\p{L}.*", RegexOption.IGNORE_CASE)
    private val PLACE_OF_CARE = listOf("clinic", "hospital", "medical", "health", "care", "centre", "center").map(MatchText::fold)

    private fun prescriber(lines: List<String>): String? {
        val top = lines.take(TOP_LINES)
        top.firstOrNull { DOCTOR.matches(it) }?.let { line ->
            // "Dr. Ayesha Khan MBBS, FCPS" → "Dr. Ayesha Khan"
            val name = line.split(',', '(', '|').first().trim()
            val words = name.split(' ').takeWhile { word -> word.lowercase().trim('.') !in DEGREES }
            if (words.size >= 2) return Headings.tidy(words.joinToString(" "))
        }
        top.firstOrNull { line -> PLACE_OF_CARE.any(MatchText.of(line)::contains) && isNameLike(line) }?.let { return displayName(it) }
        return issuer(lines, DocumentType.PRESCRIPTION)
    }

    private val DEGREES = setOf("mbbs", "fcps", "frcs", "mrcp", "md", "mcps", "phd", "bds", "fcp", "dch", "dgo")

    private fun isNameLike(line: String): Boolean {
        val cleaned = cleanName(line)
        return cleaned.length in 3..MAX_NAME && Headings.looksLikeTitle(cleaned) && Headings.readsAsWords(cleaned) &&
            cleaned.count(Char::isDigit) * 4 <= cleaned.length
    }

    private val CARRIER_WORDS = listOf(
        "airlines", "airline", "airways", "air", "railways", "railway", "express", "travels", "travel", "cinema",
        "cinemas", "cineplex", "bus", "coach", "lines",
    ).map(MatchText::fold)

    private fun carrier(lines: List<String>): String? {
        val top = lines.take(TOP_LINES)
        top.firstOrNull { line -> CARRIER_WORDS.any(MatchText.of(line)::contains) && isNameLike(line) && !MatchText.of(line).contains("ticket") }
            ?.let { return displayName(it) }
        return issuer(lines, DocumentType.TICKET)
    }

    // ── Letters ─────────────────────────────────────────────────────────

    private val SUBJECT = Regex("^(?:subject|sub|re)\\s*[:.\\-]\\s*(.+)$", RegexOption.IGNORE_CASE)

    private fun letter(lines: List<String>, text: String): String {
        val subject = subject(lines)
        if (subject != null) return join(DocumentType.LETTER.tag, subject)
        return join(DocumentType.LETTER.tag, issuer(lines, DocumentType.LETTER), DateFinder.pick(text)?.format())
    }

    /** The text of a "Subject:", "Sub:" or "Re:" line, when it reads as words. */
    private fun subject(lines: List<String>): String? = lines.firstNotNullOfOrNull { SUBJECT.find(it)?.groupValues?.get(1) }
        ?.let(Headings::tidy)
        ?.takeIf { Headings.looksLikeTitle(it) && Headings.readsAsWords(it) }

    // ── ID cards ────────────────────────────────────────────────────────

    private val NAME_LABEL = Regex("^(?:full\\s+)?name\\s*[:.\\-]?\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val OTHER_NAME_LABELS = listOf("father", "husband", "mother", "guardian", "spouse").map(MatchText::fold)
    private val PERSON_NAME = Regex("^[\\p{L}][\\p{L}.' -]{1,40}$")

    private fun idCard(lines: List<String>, text: String): String {
        val words = MatchText.of(text)
        val kind = when {
            words.contains("passport") && !words.contains(MatchText.fold("passport size")) -> "Passport"
            listOf("driving licence", "driving license", "driver license", "drivers license", "driver s license", "driver s licence")
                .any { words.contains(MatchText.fold(it)) } -> "Driving licence"
            else -> DocumentType.ID_CARD.tag!!
        }
        return join(kind, holderName(lines))
    }

    /**
     * The name after a "Name" label ("Name: Ayesha Khan", or "Name" with the
     * name on the next line); on a passport, given names and surname.
     */
    private fun holderName(lines: List<String>): String? {
        lines.forEachIndexed { index, line ->
            val lower = MatchText.of(line)
            if (OTHER_NAME_LABELS.any(lower::contains)) return@forEachIndexed
            val match = NAME_LABEL.find(line) ?: return@forEachIndexed
            val sameLine = match.groupValues[1].trim()
            val candidate = sameLine.ifEmpty { lines.getOrNull(index + 1).orEmpty() }
            if (isPersonName(candidate)) return Headings.tidy(candidate)
        }
        val given = labelledValue(lines, GIVEN_NAMES)
        val surname = labelledValue(lines, SURNAME)
        if (given != null && surname != null) {
            val full = "$given $surname"
            if (isPersonName(full)) return Headings.tidy(full)
        }
        return null
    }

    private val GIVEN_NAMES = Regex("^given\\s+names?\\s*[:.\\-]?\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val SURNAME = Regex("^sur\\s?name\\s*[:.\\-]?\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val ONE_NAME = Regex("^[\\p{L}][\\p{L}' -]{0,30}$")

    private fun labelledValue(lines: List<String>, label: Regex): String? {
        lines.forEachIndexed { index, line ->
            val match = label.find(line) ?: return@forEachIndexed
            val value = match.groupValues[1].trim().ifEmpty { lines.getOrNull(index + 1).orEmpty().trim() }
            if (ONE_NAME.matches(value) && Headings.readsAsWords(value)) return value
        }
        return null
    }

    private fun isPersonName(text: String): Boolean {
        if (!PERSON_NAME.matches(text)) return false
        val words = text.split(' ').filter { it.isNotBlank() }
        if (words.size !in 2..5) return false
        val folded = MatchText.of(text)
        return Headings.readsAsWords(text) && NOT_A_NAME.none(folded::contains) && OTHER_NAME_LABELS.none(folded::contains)
    }

    // ── Certificates ────────────────────────────────────────────────────

    private val RECIPIENT = Regex(
        "(?:presented to|awarded to|certify that|certifies that|certified that|conferred (?:up)?on|granted to)\\s*[:,]?\\s*(.*)$",
        RegexOption.IGNORE_CASE,
    )
    private val TITLES = Regex("^(?:mr|mrs|ms|miss|dr)\\.?\\s+", RegexOption.IGNORE_CASE)
    private val NAME_END = Regex("\\s+(?:has|have|for|who|in|on|s/o|d/o|w/o|son|daughter|of|is|was)\\b.*$", RegexOption.IGNORE_CASE)

    private fun certificate(lines: List<String>): String {
        val top = lines.take(TOP_LINES)
        val index = top.indexOfFirst { line ->
            val words = MatchText.of(line)
            (words.contains("certificate") || words.contains("diploma")) && Headings.looksLikeTitle(line)
        }
        var heading = if (index >= 0) Headings.tidy(top[index]) else null
        // "CERTIFICATE" on one line and "OF ACHIEVEMENT" on the next.
        if (heading != null && heading.split(' ').size == 1) {
            val next = top.getOrNull(index + 1)
            if (next != null && next.trim().lowercase().startsWith("of ")) heading = Headings.tidy("${top[index]} $next")
        }
        // A degree names itself: "Bachelor of Science in Electrical Engineering".
        if (heading == null) heading = lines.firstOrNull { DEGREE.matches(it) && Headings.readsAsWords(it) }?.let(Headings::tidy)
        return join(heading ?: DocumentType.CERTIFICATE.tag, recipient(lines))
    }

    private val DEGREE = Regex("^(?:bachelor|master|doctor|diploma)s?\\s+of\\s+.+", RegexOption.IGNORE_CASE)

    private fun recipient(lines: List<String>): String? {
        lines.forEachIndexed { index, line ->
            val match = RECIPIENT.find(line) ?: return@forEachIndexed
            val rest = match.groupValues[1].trim()
            val candidate = (rest.ifEmpty { lines.getOrNull(index + 1).orEmpty() })
                .replace(TITLES, "")
                .replace(NAME_END, "")
                .trim()
                .trimEnd(',', '.', ';')
            if (isPersonName(candidate)) return Headings.tidy(candidate)
        }
        return null
    }

    // ── Forms ───────────────────────────────────────────────────────────

    /** A form's heading names it ("Admission Form", "Application for Employment"). */
    private fun form(lines: List<String>): String {
        val top = lines.take(TOP_LINES)
        val heading = top.firstOrNull { line ->
            MatchText.of(line).contains("form") && Headings.looksLikeTitle(line) && Headings.readsAsWords(line)
        } ?: top.firstOrNull { line -> !line.contains(':') && !line.contains("__") && isNameLike(line) }
        return heading?.let(Headings::tidy) ?: DocumentType.FORM.tag!!
    }

    private const val MAX_NAME = 40
    private const val MAX_TITLE_PLAIN = 60
}
