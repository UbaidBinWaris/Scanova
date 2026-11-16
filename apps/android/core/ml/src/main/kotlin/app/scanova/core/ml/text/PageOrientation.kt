package app.scanova.core.ml.text

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How well one recognition pass reads as upright text.
 *
 * Measured on the lines whose angle is within [PageOrientation.UPRIGHT_TOLERANCE]
 * of level ("upright lines"): their letters and digits, ML Kit's confidence for
 * them, and how many of their words are common words. Sideways or upside-down
 * text either comes back with a tilted angle, with low confidence, or as
 * letters that do not form words, so all three count.
 */
data class PassQuality(
    /** Letters and digits on upright lines. */
    val uprightChars: Int,
    /** Letters and digits on all lines. */
    val totalChars: Int,
    /** Character-weighted mean confidence of the upright lines (a neutral default when the recogniser gave none). */
    val confidence: Float,
    /** Share of the upright lines' words (two or more letters) that are common words, 0..1. */
    val wordRate: Float,
    /**
     * The clockwise angle (90, 180 or 270) at which most of the text lies, when
     * most of it is not upright; null otherwise.
     */
    val tiltedQuadrant: Int?,
) {
    /** Upright text the recogniser is sure of, made of real words, scores highest. */
    val score: Float = uprightChars * confidence * (WORD_FLOOR + (1f - WORD_FLOOR) * wordRate)

    val uprightShare: Float = if (totalChars == 0) 0f else uprightChars.toFloat() / totalChars

    /** Clearly upright already: no other orientation needs to be tried. */
    val confidentlyUpright: Boolean =
        uprightChars >= CONFIDENT_CHARS && uprightShare >= CONFIDENT_SHARE &&
            (confidence >= CONFIDENT_CONFIDENCE || wordRate >= CONFIDENT_WORD_RATE)

    private companion object {
        /** Unknown words still count a little: names, codes, other Latin-script languages. */
        const val WORD_FLOOR = 0.35f
        const val CONFIDENT_CHARS = 40
        const val CONFIDENT_SHARE = 0.8f
        const val CONFIDENT_CONFIDENCE = 0.75f
        const val CONFIDENT_WORD_RATE = 0.3f
    }
}

/**
 * Finds the quarter turn that makes a page's text upright, from text
 * recognition alone (no extra model).
 *
 * The first pass (the one that reads the page's text anyway) decides most
 * pages on its own: text that comes back level, confident and in real words
 * is upright. Only when it is not ([PassQuality.confidentlyUpright] false),
 * the other quarter turns are tried on a reduced copy — the one the tilted
 * lines point to first — and a turn wins only when it reads clearly better
 * than the page as it is ([WIN_RATIO]). Anything doubtful keeps the page as
 * it is: a page wrongly turned is worse than one left alone.
 */
object PageOrientation {
    /** Degrees either side of level that still count as an upright line. */
    const val UPRIGHT_TOLERANCE = 30f

    /** A turn must read this much better than the page as it is. */
    const val WIN_RATIO = 1.6f

    /** And reach this score at least (about 25 confident characters of real words). */
    const val MIN_WIN_SCORE = 18f

    /** Confidence assumed for lines the recogniser gave none for. */
    const val UNKNOWN_CONFIDENCE = 0.6f

    private val WORD = Regex("\\p{L}{2,}")

    fun quality(text: RecognizedText): PassQuality {
        var uprightChars = 0
        var totalChars = 0
        var weightedConfidence = 0f
        var knownConfidenceChars = 0
        var words = 0
        var commonWords = 0
        val tiltedChars = IntArray(4)
        for (line in text.lines) {
            val chars = line.text.count(Char::isLetterOrDigit)
            if (chars == 0) continue
            totalChars += chars
            val quadrant = quadrantOf(line.angle) ?: continue
            if (quadrant != 0) {
                tiltedChars[quadrant / 90] += chars
                continue
            }
            uprightChars += chars
            if (line.confidence.isFinite() && line.confidence > 0f) {
                weightedConfidence += line.confidence.coerceAtMost(1f) * chars
                knownConfidenceChars += chars
            }
            for (match in WORD.findAll(line.text)) {
                words++
                if (CommonWords.contains(match.value)) commonWords++
            }
        }
        // Lines without a confidence take the neutral default, so they neither help nor sink a pass.
        val unknownChars = uprightChars - knownConfidenceChars
        val confidence = if (uprightChars == 0) {
            0f
        } else {
            (weightedConfidence + UNKNOWN_CONFIDENCE * unknownChars) / uprightChars
        }
        val tiltedTotal = tiltedChars.sum()
        val dominant = (1..3).maxBy { tiltedChars[it] }
        val tiltedQuadrant = if (tiltedTotal * 2 > totalChars && tiltedChars[dominant] > 0) dominant * 90 else null
        return PassQuality(
            uprightChars = uprightChars,
            totalChars = totalChars,
            confidence = confidence,
            wordRate = if (words == 0) 0f else commonWords.toFloat() / words,
            tiltedQuadrant = tiltedQuadrant,
        )
    }

    /**
     * The quarter turns (clockwise degrees) worth trying after [first], most
     * likely first. Text lying at a clockwise angle q is made upright by
     * turning the page the other way (360 - q); q itself follows in case the
     * recogniser's angle runs the other way, then the rest.
     */
    fun candidateTurns(first: PassQuality): List<Int> {
        val tilted = first.tiltedQuadrant
        val ordered = if (tilted != null) {
            listOf((360 - tilted) % 360, tilted, 180, 90, 270)
        } else {
            // Nothing readable sideways: a landscape page shot in portrait is the usual case.
            listOf(90, 270, 180)
        }
        return ordered.filter { it != 0 }.distinct()
    }

    /**
     * Returns the clockwise quarter turn (0, 90, 180 or 270) that makes the
     * page upright. [first] is the pass at the page's current orientation;
     * [probe] recognises the page turned by a given angle (on a reduced copy)
     * and returns null when recognition failed.
     */
    suspend fun search(first: PassQuality, probe: suspend (turn: Int) -> PassQuality?): Int {
        if (first.confidentlyUpright || first.totalChars == 0) return 0
        var best = 0
        var bestQuality: PassQuality? = null
        for (turn in candidateTurns(first)) {
            val quality = probe(turn) ?: continue
            if (quality.confidentlyUpright && wins(quality, first)) return turn
            if (bestQuality == null || quality.score > bestQuality.score) {
                best = turn
                bestQuality = quality
            }
        }
        return if (bestQuality != null && wins(bestQuality, first) && bestQuality.uprightShare >= MIN_WIN_SHARE) best else 0
    }

    private fun wins(candidate: PassQuality, first: PassQuality): Boolean =
        candidate.score >= MIN_WIN_SCORE && candidate.score >= first.score * WIN_RATIO

    /**
     * 0, 90, 180 or 270: the quarter turn within [UPRIGHT_TOLERANCE] of a
     * line's clockwise [angle]; null for a line in between (a skewed photo),
     * which says nothing about the orientation. A missing angle counts as level.
     */
    fun quadrantOf(angle: Float): Int? {
        if (!angle.isFinite()) return 0
        val normalized = ((angle % 360f) + 360f) % 360f
        val quadrant = (normalized / 90f).roundToInt() % 4
        val distance = abs(normalized - (normalized / 90f).roundToInt() * 90f)
        return if (distance <= UPRIGHT_TOLERANCE) quadrant * 90 else null
    }

    /** Normalises any multiple of 90 to 0, 90, 180 or 270. */
    fun normalizeTurn(degrees: Int): Int = ((degrees % 360) + 360) % 360

    private const val MIN_WIN_SHARE = 0.6f
}
