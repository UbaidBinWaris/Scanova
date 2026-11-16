package app.scanova.core.ml.insight

/**
 * Text prepared for keyword matching: lower case, one space between words,
 * every run of punctuation a word break, and the letter/digit mix-ups OCR
 * makes most folded away inside words ("lNV0ICE" and "INVOICE" both become
 * "invoice"; "Payrnent" becomes "payment"). Keywords go through the same
 * folding, so they always meet the text on equal terms.
 *
 * The folded text is only ever used to find keywords, never shown.
 */
internal class MatchText private constructor(private val padded: String) {

    /** How often [phrase] (already [fold]ed) occurs as whole words. */
    fun count(phrase: String): Int {
        if (phrase.isEmpty()) return 0
        val needle = " $phrase "
        var count = 0
        var from = 0
        while (true) {
            val at = padded.indexOf(needle, from)
            if (at < 0) return count
            count++
            // Words are separated by single spaces: the next match may start at this one's last space.
            from = at + needle.length - 1
        }
    }

    fun contains(phrase: String): Boolean = count(phrase) > 0

    companion object {
        fun of(text: String): MatchText = MatchText(" ${fold(text)} ")

        /** Folds [text] the way documents are folded. */
        fun fold(text: String): String {
            val words = ArrayList<String>()
            val token = StringBuilder()
            fun flush() {
                if (token.isNotEmpty()) {
                    splitAndFold(token.toString(), words)
                    token.clear()
                }
            }
            for (raw in text) {
                val c = when (raw) {
                    // A bar inside a word is an I or an l; the prescription sign is "Rx".
                    '|' -> 'l'
                    '℞' -> {
                        flush()
                        words += "rx"
                        continue
                    }
                    else -> raw
                }
                if (c.isLetterOrDigit()) token.append(c.lowercaseChar()) else flush()
            }
            flush()
            return words.joinToString(" ")
        }

        /**
         * A token that is mostly letters is a word: its digits are OCR mix-ups
         * (0→o, 1→i, 5→s) and l/i are the same letter. A token that is mostly
         * digits is split where letters and digits meet ("500mg" → "500 mg").
         */
        private fun splitAndFold(token: String, into: MutableList<String>) {
            val letters = token.count { it.isLetter() }
            val digits = token.length - letters
            if (letters == 0) {
                into += token
                return
            }
            if (digits > 0 && digits >= letters) {
                var start = 0
                for (i in 1..token.length) {
                    if (i == token.length || token[i].isDigit() != token[i - 1].isDigit()) {
                        val part = token.substring(start, i)
                        into += if (part[0].isDigit()) part else foldWord(part)
                        start = i
                    }
                }
                return
            }
            into += foldWord(token)
        }

        private fun foldWord(word: String): String {
            val mapped = StringBuilder(word.length)
            for (c in word) {
                mapped.append(
                    when (c) {
                        '0' -> 'o'
                        '1', 'l' -> 'i'
                        '5' -> 's'
                        else -> c
                    },
                )
            }
            return mapped.toString().replace("rn", "m").replace("vv", "w")
        }
    }
}
