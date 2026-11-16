package app.scanova.core.ml.insight

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Measures the type rules and title rules (docs/ARCHITECTURE.md §11: decisions
 * are measured, not assumed) on three sets: [EVALUATION_SET], which the rules
 * were tuned on; [HELD_OUT_SET], written afterwards (69 % type accuracy on its
 * first run, before the rules were generalised from its misses); and
 * [FRESH_SET], never tuned on, reported without a gate. The report — accuracy
 * per type, title acceptance, every miss — is printed and written to
 * `build/reports/insights-evaluation.txt`.
 *
 * Gates (as §11.6): type ≥ 90 % and titles ≥ 80 % on the tuning set; on the
 * held-out set type ≥ 75 % and titles ≥ 60 %, so a rule change that only
 * fits the tuning set shows. A document of no known type is never typed.
 */
class DocumentInsightsEvaluationTest {

    private data class Outcome(val sample: Sample, val insight: DocumentInsight) {
        val typeRight: Boolean get() = insight.type == sample.type
        val titleRight: Boolean get() = insight.title == sample.title
    }

    private val tuning = EVALUATION_SET.map { Outcome(it, DocumentInsights.analyze(it.text)) }
    private val heldOut = HELD_OUT_SET.map { Outcome(it, DocumentInsights.analyze(it.text)) }

    /** Reported only, never gated: see [FRESH_SET]. */
    private val fresh = FRESH_SET.map { Outcome(it, DocumentInsights.analyze(it.text)) }

    @Test
    fun `type accuracy meets the gate`() {
        val report = report()
        assertTrue("Type accuracy ${percent(typeAccuracy(tuning))} is below 90 %\n$report", typeAccuracy(tuning) >= TYPE_GATE)
        assertTrue(
            "Held-out type accuracy ${percent(typeAccuracy(heldOut))} is below 75 %\n$report",
            typeAccuracy(heldOut) >= HELD_OUT_TYPE_GATE,
        )
    }

    @Test
    fun `every type is recognised most of the time`() {
        val report = report()
        for (type in DocumentType.entries) {
            val samples = tuning.filter { it.sample.type == type }
            if (samples.isEmpty()) continue
            val accuracy = typeAccuracy(samples)
            assertTrue("${type.id}: ${percent(accuracy)} is below 75 %\n$report", accuracy >= PER_TYPE_GATE)
        }
    }

    @Test
    fun `documents of no known type are never given one`() {
        val wronglyTyped = (tuning + heldOut).filter { it.sample.type == DocumentType.OTHER && it.insight.type != DocumentType.OTHER }
        assertTrue("Typed although they are not: ${wronglyTyped.map { it.sample.name }}\n${report()}", wronglyTyped.isEmpty())
    }

    @Test
    fun `titles are accepted often enough`() {
        val report = report()
        assertTrue("Title acceptance ${percent(titleAcceptance(tuning))} is below 80 %\n$report", titleAcceptance(tuning) >= TITLE_GATE)
        assertTrue(
            "Held-out title acceptance ${percent(titleAcceptance(heldOut))} is below 60 %\n$report",
            titleAcceptance(heldOut) >= HELD_OUT_TITLE_GATE,
        )
    }

    @Test
    fun `analysis is fast enough to run anywhere`() {
        val text = EVALUATION_SET.joinToString("\n\n") { it.text }
        // Warm up, then time a whole long document.
        repeat(3) { DocumentInsights.analyze(text) }
        val started = System.nanoTime()
        repeat(10) { DocumentInsights.analyze(text) }
        val millis = (System.nanoTime() - started) / 1_000_000.0 / 10
        assertTrue("A long document took $millis ms", millis < MAX_MILLIS)
    }

    private fun typeAccuracy(outcomes: List<Outcome>): Double = outcomes.count { it.typeRight }.toDouble() / outcomes.size

    private fun titleAcceptance(outcomes: List<Outcome>): Double = outcomes.count { it.titleRight }.toDouble() / outcomes.size

    private fun report(): String {
        val text = (
            section("Tuning set", tuning) + listOf("") + section("Held-out set", heldOut) + listOf("") +
                section("Fresh set (never tuned on, no gate)", fresh)
            ).joinToString("\n")
        println(text)
        runCatching {
            File("build/reports").mkdirs()
            File("build/reports/insights-evaluation.txt").writeText(text + "\n")
        }
        return text
    }

    private fun section(name: String, outcomes: List<Outcome>): List<String> {
        val lines = ArrayList<String>()
        lines += "$name: ${outcomes.size} samples, type accuracy ${percent(typeAccuracy(outcomes))}, " +
            "titles accepted ${percent(titleAcceptance(outcomes))}"
        lines += "%-16s %7s %6s %7s".format("type", "samples", "type", "title")
        for (type in DocumentType.entries) {
            val samples = outcomes.filter { it.sample.type == type }
            if (samples.isEmpty()) continue
            lines += "%-16s %7d %6s %7s".format(type.id, samples.size, percent(typeAccuracy(samples)), percent(titleAcceptance(samples)))
        }
        val misses = outcomes.filter { !it.typeRight || !it.titleRight }
        if (misses.isNotEmpty()) {
            lines += "Misses:"
            for (miss in misses) {
                val scores = DocumentClassifier.classify(miss.sample.text).scores.entries.joinToString { "${it.key.id}=${it.value}" }
                lines += "- ${miss.sample.name}: type ${miss.insight.type.id} (expected ${miss.sample.type.id}) [$scores]"
                if (!miss.titleRight) lines += "    title \"${miss.insight.title}\" (expected \"${miss.sample.title}\")"
            }
        }
        return lines
    }

    private fun percent(value: Double): String = "%.0f %%".format(value * 100)

    private companion object {
        const val TYPE_GATE = 0.90
        const val PER_TYPE_GATE = 0.75
        const val TITLE_GATE = 0.80
        const val HELD_OUT_TYPE_GATE = 0.75
        const val HELD_OUT_TITLE_GATE = 0.60
        const val MAX_MILLIS = 250.0
    }
}
