package app.scanova.core.ml.insight

/** Small helpers for turning lines of recognised text into title parts. */
internal object Headings {
    private const val MIN_LETTERS = 3
    private const val MAX_LINES_CONSIDERED = 12
    private val SPACES = Regex("[\\s\\u00A0\\u202F]+")
    private const val DECORATION = " -–—:.,;|_*#=~•·>"

    /** Words kept in lower case inside a title ("Certificate of Achievement"). */
    private val SMALL_WORDS = setOf("a", "an", "and", "as", "at", "by", "for", "in", "of", "on", "or", "the", "to", "with")

    /**
     * Short words that are words, not acronyms, when a line is all capitals
     * (any other word of three letters or fewer stays as written: HBL, PIA, NOC).
     */
    private val SHORT_WORDS = setOf(
        "al", "air", "all", "art", "bus", "big", "car", "co", "day", "dr", "gas", "inc", "law", "ltd", "new", "net",
        "oil", "old", "one", "our", "pay", "pvt", "sir", "tax", "top", "two", "way", "you", "mr", "mrs", "ms", "no",
        "bin", "ul", "ud", "ur", "bay", "sea", "sky", "sun", "red", "tea", "spa", "inn", "hub", "pro", "box", "fit",
        "gym", "pet", "toy", "map", "pen", "cup", "bag", "key", "bar", "pub", "city", "age", "end", "fun", "hot",
        // Parts of names
        "ali", "din", "sui", "haq", "gul", "jan", "mir", "rao", "dar", "nur", "zia", "abu", "ibn", "san", "del", "aga",
        "van", "von", "ben", "lee", "kim", "max", "sam", "tom", "ann", "eve", "joe", "roy", "ray", "bibi",
    )

    private val TWO_LETTER_ACRONYMS = setOf("id", "uk", "us", "ai", "eu", "it")

    /** Non-empty lines with their spacing collapsed, in order. */
    fun lines(text: String): List<String> =
        text.lineSequence().map { it.replace(SPACES, " ").trim() }.filter { it.isNotEmpty() }.toList()

    /** The first line near the top that reads like a heading, tidied; null when there is none. */
    fun heading(text: String): String? = lines(text)
        .take(MAX_LINES_CONSIDERED)
        .firstOrNull { looksLikeTitle(it) && readsAsWords(it) && !isLabelOrAddress(it) }
        ?.let(::tidy)
        ?.takeIf { it.count(Char::isLetter) >= MIN_LETTERS }

    private val LABEL = Regex("^[\\p{L}][\\p{L} .'/]{0,24}:\\s*\\S")
    private val SALUTATION = Regex("^(?:dear|hi|hello|respected)\\b", RegexOption.IGNORE_CASE)

    /** "From: Sara <sara@example.com>", "Date: …", "Hi Bilal,", a web or e-mail address: never a heading. */
    private fun isLabelOrAddress(line: String): Boolean =
        LABEL.containsMatchIn(line) || SALUTATION.containsMatchIn(line) || '@' in line ||
            line.contains("http", ignoreCase = true) || line.contains("www.", ignoreCase = true)

    /** Mostly letters: dates, amounts, phone numbers and reference codes are not headings. */
    fun looksLikeTitle(line: String): Boolean {
        val letters = line.count(Char::isLetter)
        if (letters < MIN_LETTERS) return false
        val meaningful = line.count { !it.isWhitespace() }
        return letters * 2 >= meaningful
    }

    /**
     * Latin-script words that could be real words: a vowel each, no long
     * consonant runs. Text in other scripts always passes. Keeps OCR noise
     * (Urdu read by the Latin model, a smudge read as "Jl Lsl") out of titles.
     */
    fun readsAsWords(line: String): Boolean {
        val words = Regex("\\p{L}+").findAll(line).map { it.value }.toList()
        val latin = words.filter { word -> word.any { it in 'a'..'z' || it in 'A'..'Z' } }
        if (latin.isEmpty()) return words.isNotEmpty()
        val plausible = latin.filter(::plausibleWord)
        return plausible.size * 2 >= latin.size && plausible.any { it.length >= MIN_LETTERS }
    }

    private fun plausibleWord(word: String): Boolean {
        val lower = word.lowercase()
        if (lower.length == 1) return lower in "aio"
        if (lower.none { it in "aeiouy" }) return lower.length <= 4 && word.all(Char::isUpperCase) // acronyms: HBL, PTCL
        var run = 0
        for (c in lower) {
            run = if (c in "aeiouy") 0 else run + 1
            if (run >= 5) return false
        }
        return true
    }

    /** Trims decoration and turns an all-capitals line into title case ("IMTIAZ SUPER MARKET" → "Imtiaz Super Market"). */
    fun tidy(line: String): String {
        val trimmed = line.replace(SPACES, " ").trim().trim { it in DECORATION }.trim()
            .split(' ').joinToString(" ", transform = ::fixCapitalI)
        val letters = trimmed.filter(Char::isLetter)
        val shouting = letters.length >= 2 && letters.none(Char::isLowerCase) && letters.any { it in 'A'..'Z' }
        if (!shouting) return trimmed
        return trimmed.split(' ').mapIndexed { index, word -> titleCaseWord(word, first = index == 0) }.joinToString(" ")
    }

    /** In a word of capitals, a lower-case l is an I that OCR misread: "lMTlAZ" → "IMTIAZ". */
    private fun fixCapitalI(word: String): String {
        if ('l' !in word) return word
        val others = word.filter { it.isLetter() && it != 'l' }
        return if (others.length >= 2 && others.all(Char::isUpperCase)) word.replace('l', 'I') else word
    }

    private fun titleCaseWord(word: String, first: Boolean): String {
        // "K-ELECTRIC" → "K-Electric", "AL-FATAH" → "Al-Fatah".
        if ('-' in word.trim('-')) return word.split('-').joinToString("-") { titleCaseWord(it, first) }
        val letters = word.filter(Char::isLetter)
        val lower = letters.lowercase()
        val vowels = lower.any { it in "aeiouy" }
        val acronym = lower !in SMALL_WORDS && lower !in SHORT_WORDS && when {
            // Two letters with a vowel are a word ("UP", "GO"), except a few well-known acronyms.
            letters.length <= 2 -> !vowels || lower in TWO_LETTER_ACRONYMS
            letters.length == 3 -> true
            else -> letters.length <= 5 && !vowels
        }
        return when {
            letters.isEmpty() -> word
            !first && lower in SMALL_WORDS -> word.lowercase()
            acronym -> word // HBL, PIA, KFC, PTCL, SNGPL
            else -> word.lowercase().replaceFirstChar { it.titlecase() }
        }
    }

    /** Cuts [text] to [max] characters at a word boundary, unless that would throw most of it away. */
    fun shorten(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.substring(0, max)
        val lastSpace = cut.lastIndexOf(' ')
        return (if (lastSpace >= max / 2) cut.substring(0, lastSpace) else cut).trim().trimEnd { it in DECORATION }
    }
}
