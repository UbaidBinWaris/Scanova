package app.scanova.core.ml.insight

/** The type a document was given and why. */
data class Classification(
    val type: DocumentType,
    /** Points of the winning type ([DocumentClassifier.MIN_SCORE] or more), 0 for [DocumentType.OTHER]. */
    val score: Double,
    /** Points per type that had any, highest first, for tests and tuning. */
    val scores: Map<DocumentType, Double>,
)

/**
 * Classifies a document from its recognised text with transparent rules: no
 * model, no network. Every type has a list of keyword cues with points
 * (the tables below), so a decision can always be explained and the rules
 * changed in one place; `DocumentInsightsEvaluationTest` measures every change.
 *
 * How a type scores:
 * - each cue found in the text adds its points once (a cue is a word or
 *   phrase, matched on whole words after OCR folding, see [MatchText]);
 * - an *anchor* cue is one that only this type of document says ("receipt",
 *   "bill to", "boarding pass"): a type needs at least one anchor to qualify,
 *   so a pile of generic words (total, date, name) never decides on its own;
 * - an anchor in the heading (the first lines) adds [Cue.headBonus] more:
 *   "INVOICE" as a title outweighs "invoice" mentioned in a letter;
 * - a few layout signals add points: prose for letters, blank "Label:" lines
 *   for forms, dated amount rows for statements, an MRZ line for ID cards.
 *
 * The type with the most points wins if it reaches [MIN_SCORE]; otherwise the
 * document is [DocumentType.OTHER]. English (Latin script) only: on the phone
 * Urdu is not recognised as text at all.
 */
object DocumentClassifier {
    /** Points a type needs to be chosen. */
    const val MIN_SCORE = 4.0

    /** Only the start of a long document is read: the first pages say what it is. */
    private const val MAX_CHARS = 6000
    private const val HEAD_LINES = 8

    fun classify(text: String): Classification {
        val sample = text.take(MAX_CHARS)
        if (sample.isBlank()) return Classification(DocumentType.OTHER, 0.0, emptyMap())
        val lines = Headings.lines(sample)
        val all = MatchText.of(sample)
        val head = MatchText.of(lines.take(HEAD_LINES).joinToString("\n"))
        val layout = Layout.of(lines)

        val scores = LinkedHashMap<DocumentType, Double>()
        for ((type, cues) in RULES) {
            var score = 0.0
            var anchored = false
            for (cue in cues) {
                if (!cue.matches(all)) continue
                score += cue.points
                if (cue.anchor) {
                    anchored = true
                    if (cue.headBonus > 0 && cue.matches(head)) score += cue.headBonus
                }
            }
            val extra = layout.points(type)
            score += extra.points
            anchored = anchored || extra.anchors
            if (anchored && score > 0) scores[type] = score
        }
        val ranked = scores.entries.sortedWith(compareByDescending<Map.Entry<DocumentType, Double>> { it.value }.thenBy { PRIORITY.indexOf(it.key) })
        val best = ranked.firstOrNull()
        val sorted = ranked.associate { it.key to it.value }
        return if (best != null && best.value >= MIN_SCORE) {
            Classification(best.key, best.value, sorted)
        } else {
            Classification(DocumentType.OTHER, 0.0, sorted)
        }
    }

    /** On equal points the more specific type wins. */
    private val PRIORITY = listOf(
        DocumentType.ID_CARD, DocumentType.PRESCRIPTION, DocumentType.TICKET, DocumentType.BANK_STATEMENT,
        DocumentType.CERTIFICATE, DocumentType.INVOICE, DocumentType.BILL, DocumentType.RECEIPT, DocumentType.FORM,
        DocumentType.LETTER,
    )

    // ── The rules ───────────────────────────────────────────────────────
    //
    // anchor(phrase, points, headBonus): says what the document is; at least one is needed.
    // cue(phrase, points): supports a type that has an anchor.
    // `unless`: occurrences inside these longer phrases do not count ("bill" in "bill to").

    private val RULES: Map<DocumentType, List<Cue>> = mapOf(
        DocumentType.RECEIPT to listOf(
            anchor("receipt", 3.0, head = 2.0, unless = listOf("payment receipt no")),
            anchor("cash memo", 4.0),
            anchor("thank you for shopping", 3.0),
            anchor("thank you for your purchase", 2.5),
            anchor("thank you for visiting", 2.0),
            anchor("thanks for shopping", 3.0),
            anchor("come again", 2.0),
            anchor("change due", 3.0),
            anchor("tendered", 2.5),
            anchor("cashier", 2.0),
            anchor("pos", 1.5),
            anchor("dine in", 2.0),
            anchor("take away", 1.5),
            anchor("takeaway", 1.5),
            anchor("no of items", 2.0),
            anchor("paid cash", 2.0),
            anchor("cash paid", 2.0),
            anchor("cash received", 2.0),
            anchor("thank you", 1.0, unless = listOf("thank you for your business", "thank you for your letter")),
            cue("grand total", 1.0),
            cue("balance due", 1.0),
            anchor("total items", 2.0),
            cue("fbr", 1.5),
            cue("change", 1.0),
            cue("cash", 1.0),
            cue("subtotal", 1.0),
            cue("sub total", 1.0),
            cue("total", 0.5),
            cue("qty", 0.5),
            cue("item", 0.5),
            cue("items", 0.5),
            cue("mart", 1.0),
            cue("supermarket", 1.0),
            cue("super market", 1.0),
            cue("store", 0.5),
            cue("restaurant", 1.0),
            cue("cafe", 1.0),
            cue("bakery", 1.0),
            cue("bakers", 1.0),
            cue("till", 1.0),
            cue("ntn", 0.5),
            cue("strn", 0.5),
            cue("sales tax", 0.5),
            cue("gst", 0.5),
            cue("visa", 0.5),
            cue("mastercard", 0.5),
            cue("table", 0.5),
            cue("order no", 0.5),
            cue("bill no", 1.0),
            cue("guest", 0.5),
            cue("discount", 0.5),
        ),
        DocumentType.INVOICE to listOf(
            anchor("invoice", 3.0, head = 2.0, unless = listOf("fbr invoice", "pos invoice", "fbr pos invoice")),
            anchor("proforma", 3.0),
            anchor("bill to", 3.0),
            anchor("billed to", 3.0),
            anchor("invoice to", 2.0),
            anchor("balance due", 2.5),
            anchor("remit", 2.0),
            anchor("thank you for your business", 2.0),
            cue("tax invoice", 1.0),
            cue("commercial invoice", 1.0),
            cue("invoice no", 1.5),
            cue("invoice number", 1.5),
            cue("invoice date", 1.5),
            cue("ship to", 2.0),
            cue("sold to", 1.5),
            cue("due date", 1.0),
            cue("payment due", 1.5),
            cue("amount due", 1.0),
            cue("payment terms", 2.0),
            cue("terms", 0.5),
            cue("net 30", 2.0),
            cue("net 15", 2.0),
            cue("purchase order", 1.5),
            cue("po no", 1.0),
            cue("unit price", 1.5),
            cue("description", 1.0),
            cue("quantity", 0.5),
            cue("amount", 0.3),
            cue("bank details", 1.0),
            cue("account title", 0.5),
            cue("iban", 0.5),
            cue("swift", 1.0),
            cue("remittance", 1.5),
            cue("hours", 0.5),
            cue("services", 0.5),
            cue("vat", 0.3),
        ),
        DocumentType.BILL to listOf(
            // Not "bill to" (an invoice), "bill no" (a restaurant receipt) or "bill payment" (a statement row).
            anchor("bill", 1.5, head = 1.5, unless = listOf("bill to", "billed to", "bill no", "bill from", "bill payment", "bill payments")),
            anchor("electricity bill", 3.0),
            anchor("gas bill", 3.0),
            anchor("water bill", 3.0),
            anchor("telephone bill", 3.0),
            anchor("phone bill", 3.0),
            anchor("internet bill", 3.0),
            anchor("utility bill", 3.0),
            anchor("electricity", 2.5),
            anchor("utility", 2.0),
            anchor("meter", 2.0),
            anchor("units consumed", 2.5),
            anchor("kwh", 2.5),
            anchor("billing month", 3.0),
            anchor("bill month", 3.0),
            anchor("billing period", 2.0),
            anchor("consumer no", 2.5),
            anchor("consumer number", 2.5),
            anchor("consumer id", 2.5),
            anchor("payable within due date", 3.0),
            anchor("after due date", 2.5),
            anchor("previous reading", 2.0),
            anchor("present reading", 2.0),
            anchor("tariff", 2.0),
            anchor("postpaid", 2.0),
            anchor("sui gas", 2.5),
            anchor("k electric", 3.0),
            anchor("lesco", 3.0),
            anchor("iesco", 3.0),
            anchor("mepco", 3.0),
            anchor("fesco", 3.0),
            anchor("gepco", 3.0),
            anchor("pesco", 3.0),
            anchor("hesco", 3.0),
            anchor("qesco", 3.0),
            anchor("sngpl", 3.0),
            anchor("ssgc", 3.0),
            anchor("ptcl", 3.0),
            anchor("wapda", 3.0),
            cue("electric", 1.0),
            cue("gas", 1.0),
            cue("water", 1.0),
            cue("units", 1.0),
            cue("meter no", 1.0),
            cue("meter reading", 1.5),
            cue("current reading", 1.5),
            cue("bill date", 1.5),
            cue("issue date", 0.5),
            cue("consumer", 1.0),
            cue("reference no", 1.0),
            cue("account no", 0.5),
            cue("due date", 1.0),
            cue("late payment", 2.0),
            cue("surcharge", 1.5),
            cue("arrears", 2.0),
            cue("sanctioned load", 2.0),
            cue("fpa", 1.5),
            cue("fuel price adjustment", 2.5),
            cue("tv fee", 2.5),
            cue("broadband", 1.5),
            cue("internet", 1.0),
            cue("telephone", 1.0),
            cue("nayatel", 2.0),
            cue("stormfiber", 2.0),
            cue("amount payable", 1.5),
            cue("total payable", 1.0),
            cue("net payable", 1.0),
        ),
        DocumentType.ID_CARD to listOf(
            anchor("identity card", 4.0),
            anchor("id card", 3.5),
            anchor("identification card", 3.5),
            anchor("cnic", 3.0),
            anchor("nicop", 3.0),
            anchor("nadra", 3.0),
            anchor("identity number", 2.5),
            anchor("passport", 4.0, unless = listOf("passport size")),
            anchor("driving licence", 4.0),
            anchor("driving license", 4.0),
            anchor("driver license", 4.0),
            anchor("drivers license", 4.0),
            anchor("driver s license", 4.0),
            anchor("driver s licence", 4.0),
            anchor("student id", 3.5),
            anchor("student card", 3.5),
            anchor("employee id", 3.0),
            anchor("employee card", 3.5),
            anchor("staff id", 3.0),
            anchor("country of stay", 3.0),
            cue("national identity card", 1.0),
            cue("identity", 1.0),
            cue("id number", 1.5),
            cue("id no", 1.5),
            cue("passport no", 1.5),
            cue("place of birth", 1.5),
            cue("licence no", 1.5),
            cue("license no", 1.5),
            cue("date of birth", 2.0),
            cue("dob", 2.0),
            cue("date of issue", 1.5),
            cue("date of expiry", 2.0),
            cue("expiry date", 1.0),
            cue("valid until", 1.0),
            cue("valid upto", 1.0),
            cue("valid thru", 1.0),
            cue("father name", 2.5),
            cue("father s name", 2.5),
            cue("husband name", 2.5),
            cue("gender", 1.5),
            cue("sex", 1.0),
            cue("nationality", 1.5),
            cue("blood group", 1.5),
            cue("holder s signature", 2.0),
            cue("holder signature", 2.0),
            cue("islamic republic of pakistan", 1.0),
            cue("government of", 0.5),
            // The back of a national identity card.
            cue("registrar general", 2.0),
            cue("present address", 1.0),
            cue("permanent address", 1.0),
        ),
        DocumentType.CERTIFICATE to listOf(
            anchor("certificate", 3.0, head = 2.5),
            anchor("this is to certify", 3.0),
            anchor("certify that", 2.5),
            anchor("certifies that", 2.5),
            anchor("hereby certify", 1.5),
            anchor("awarded to", 3.0),
            anchor("presented to", 3.0),
            anchor("in recognition of", 3.0),
            anchor("in appreciation of", 2.5),
            anchor("diploma", 3.0),
            anchor("conferred", 2.0),
            cue("certificate of", 1.5),
            cue("is hereby awarded", 2.0),
            cue("for outstanding", 1.5),
            cue("has successfully completed", 2.5),
            cue("successfully completed", 1.5),
            cue("for successfully completing", 2.0),
            cue("completion", 1.0),
            cue("achievement", 1.5),
            cue("appreciation", 1.0),
            cue("participation", 1.5),
            cue("excellence", 1.0),
            cue("merit", 1.0),
            cue("honour", 1.0),
            cue("honor", 1.0),
            cue("degree of", 1.5),
            cue("bachelor", 1.0),
            cue("master", 1.0),
            cue("workshop", 0.5),
            cue("course", 0.5),
            cue("seminar", 0.5),
            cue("certificate no", 1.0),
        ),
        DocumentType.LETTER to listOf(
            anchor("dear", 3.0),
            anchor("to whom it may concern", 3.0),
            anchor("yours sincerely", 3.0),
            anchor("sincerely", 2.0),
            anchor("yours faithfully", 3.0),
            anchor("yours truly", 2.5),
            anchor("best regards", 2.0),
            anchor("kind regards", 2.0),
            anchor("warm regards", 2.0),
            anchor("regards", 1.0),
            anchor("subject", 1.5),
            // The usual salutation in Pakistan.
            anchor("respected sir", 3.0),
            anchor("respected madam", 3.0),
            cue("dear sir", 1.0),
            cue("dear madam", 1.0),
            cue("faithfully", 1.0),
            cue("our ref", 1.0),
            cue("your ref", 1.0),
            cue("enclosure", 1.5),
            cue("enclosures", 1.5),
            cue("encl", 1.5),
            cue("i am writing", 2.0),
            cue("we are pleased to", 1.5),
            cue("we regret", 1.5),
            cue("please find", 1.0),
            cue("do not hesitate", 2.0),
            cue("looking forward", 1.0),
            cue("letter", 1.0),
            cue("offer letter", 2.0),
            cue("appointment letter", 2.0),
        ),
        DocumentType.FORM to listOf(
            anchor("form", 2.0, head = 2.5),
            anchor("please fill", 2.5),
            anchor("fill in", 1.5),
            anchor("fill out", 1.5),
            anchor("to be filled", 2.0),
            anchor("block letters", 3.0),
            anchor("capital letters", 2.0),
            anchor("office use only", 3.5),
            anchor("for office use", 3.0),
            anchor("applicant", 2.0),
            cue("application form", 2.0),
            cue("registration form", 2.0),
            cue("admission form", 2.0),
            cue("enrollment form", 2.0),
            cue("enrolment form", 2.0),
            cue("claim form", 2.0),
            cue("request form", 2.0),
            cue("signature of applicant", 1.5),
            cue("applicant s signature", 1.5),
            cue("declaration", 1.5),
            cue("i hereby declare", 2.0),
            cue("tick", 1.0),
            cue("photograph", 1.0),
            cue("passport size", 1.5),
            cue("marital status", 1.5),
            cue("occupation", 1.0),
            cue("permanent address", 1.5),
            cue("postal address", 1.0),
            cue("emergency contact", 1.5),
            cue("next of kin", 1.5),
            cue("guardian", 1.0),
            cue("date of birth", 0.5),
        ),
        DocumentType.BANK_STATEMENT to listOf(
            anchor("bank statement", 4.0),
            anchor("account statement", 4.0),
            anchor("statement of account", 4.0),
            anchor("e statement", 3.0),
            anchor("opening balance", 3.0),
            anchor("closing balance", 3.0),
            anchor("brought forward", 2.0),
            anchor("statement period", 2.5),
            anchor("card statement", 4.0),
            anchor("statement date", 2.0),
            cue("statement", 1.0),
            cue("balance brought forward", 2.0),
            cue("carried forward", 1.5),
            cue("transaction", 1.0),
            cue("transactions", 1.0),
            cue("transaction date", 1.5),
            cue("value date", 2.0),
            cue("posting date", 1.5),
            cue("txn", 1.0),
            cue("withdrawal", 2.0),
            cue("withdrawals", 2.0),
            cue("deposit", 1.0),
            cue("deposits", 1.0),
            cue("debit", 1.5),
            cue("credit", 0.5),
            cue("cheque", 1.0),
            cue("chq", 1.0),
            cue("account no", 1.0),
            cue("account number", 1.0),
            cue("account title", 1.5),
            cue("iban", 1.0),
            cue("branch", 0.5),
            cue("branch code", 1.0),
            cue("bank", 1.0),
            cue("period from", 1.0),
            cue("available balance", 1.5),
            cue("ibft", 1.5),
            cue("funds transfer", 1.0),
            cue("atm", 1.0),
        ),
        DocumentType.PRESCRIPTION to listOf(
            anchor("prescription", 4.0),
            anchor("rx", 3.0),
            anchor("mbbs", 3.0),
            anchor("pmdc", 2.0),
            anchor("tab", 2.0),
            anchor("tablet", 1.5),
            anchor("cap", 1.5),
            anchor("capsule", 1.5),
            anchor("syp", 2.0),
            anchor("syrup", 2.0),
            anchor("inj", 1.5),
            anchor("mg", 2.0, unless = listOf("mg dl")),
            cue("tablets", 1.5),
            cue("fcps", 2.0),
            cue("frcs", 2.0),
            cue("mrcp", 2.0),
            cue("consultant", 1.0),
            cue("physician", 1.5),
            cue("dr", 1.0),
            cue("doctor", 1.0),
            cue("clinic", 1.5),
            cue("hospital", 1.0),
            cue("patient", 1.5),
            cue("patient name", 1.5),
            cue("age", 0.5),
            cue("bp", 1.0),
            cue("pulse", 1.0),
            cue("diagnosis", 2.0),
            cue("injection", 1.0),
            cue("drops", 1.0),
            cue("ointment", 1.5),
            cue("dose", 1.0),
            cue("dosage", 1.5),
            cue("once daily", 2.0),
            cue("twice daily", 2.0),
            cue("thrice daily", 2.0),
            cue("daily", 0.5),
            cue("od", 1.0),
            cue("bd", 1.5),
            cue("tds", 1.5),
            cue("bid", 1.0),
            cue("tid", 1.0),
            cue("qid", 1.0),
            cue("sos", 1.0),
            cue("after meal", 2.0),
            cue("after meals", 2.0),
            cue("before meal", 2.0),
            cue("before meals", 2.0),
            cue("after food", 2.0),
            cue("with food", 2.0),
            cue("follow up", 1.5),
            cue("next visit", 1.5),
            cue("refill", 1.0),
        ),
        DocumentType.TICKET to listOf(
            anchor("ticket", 3.0, head = 2.0),
            anchor("eticket", 3.0),
            anchor("boarding pass", 4.5),
            anchor("boarding", 1.5),
            anchor("seat", 1.5),
            anchor("flight", 2.5),
            anchor("pnr", 3.0),
            anchor("booking reference", 2.0),
            anchor("booking ref", 2.0),
            anchor("passenger", 2.0),
            anchor("departure", 2.0),
            anchor("fare", 2.0),
            anchor("admit one", 4.0),
            anchor("berth", 2.0),
            cue("e ticket", 1.5),
            cue("electronic ticket", 1.0),
            cue("ticket no", 1.0),
            cue("gate", 1.5),
            cue("seat no", 1.0),
            cue("flight no", 1.0),
            cue("booking id", 1.5),
            cue("booking no", 1.5),
            cue("passenger name", 1.0),
            cue("depart", 1.0),
            cue("departs", 1.0),
            cue("arrival", 1.5),
            cue("arrives", 0.5),
            cue("terminal", 1.5),
            cue("check in", 1.0),
            cue("baggage", 1.5),
            cue("economy", 1.5),
            cue("business class", 1.5),
            cue("cabin", 1.0),
            cue("airline", 1.5),
            cue("airlines", 1.5),
            cue("airways", 1.5),
            cue("airport", 1.0),
            cue("train", 1.5),
            cue("railway", 2.0),
            cue("railways", 2.0),
            cue("coach", 1.5),
            cue("platform", 1.5),
            cue("bus", 1.0),
            cue("journey", 1.5),
            cue("admit", 1.0),
            cue("screen", 1.0),
            cue("show time", 2.0),
            cue("showtime", 2.0),
            cue("cinema", 2.0),
            cue("venue", 1.5),
            cue("event", 1.0),
            cue("row", 0.5),
        ),
    )

    private fun anchor(phrase: String, points: Double, head: Double = 0.0, unless: List<String> = emptyList()) =
        Cue(MatchText.fold(phrase), points, anchor = true, headBonus = head, unless = unless.map(MatchText::fold))

    private fun cue(phrase: String, points: Double, unless: List<String> = emptyList()) =
        Cue(MatchText.fold(phrase), points, anchor = false, headBonus = 0.0, unless = unless.map(MatchText::fold))
}

/** One keyword rule; see [DocumentClassifier]. */
internal class Cue(
    val phrase: String,
    val points: Double,
    val anchor: Boolean,
    val headBonus: Double,
    private val unless: List<String>,
) {
    fun matches(text: MatchText): Boolean {
        val found = text.count(phrase)
        if (found == 0) return false
        if (unless.isEmpty()) return true
        return found > unless.sumOf { text.count(it) }
    }
}

/** Signals from the shape of the text rather than its words. */
internal class Layout private constructor(
    private val functionWords: Int,
    private val blankLabels: Int,
    private val datedAmountLines: Int,
    private val amountLines: Int,
    private val totalLine: Boolean,
    private val mrzLines: Int,
    private val idNumbers: Int,
    private val chars: Int,
) {
    class Extra(val points: Double, val anchors: Boolean)

    private val shortText: Boolean get() = chars < SHORT_TEXT_CHARS

    fun points(type: DocumentType): Extra = when (type) {
        // Letters are written in sentences.
        DocumentType.LETTER -> Extra(
            when {
                functionWords >= 50 -> 3.0
                functionWords >= 25 -> 2.0
                else -> 0.0
            },
            anchors = false,
        )
        // Forms are lists of labels waiting for answers: enough blanks make a form on their own.
        DocumentType.FORM -> when {
            blankLabels >= 5 -> Extra(4.0, anchors = true)
            blankLabels >= 3 -> Extra(2.0, anchors = false)
            else -> Extra(0.0, anchors = false)
        }
        // Statements are rows of dated amounts.
        DocumentType.BANK_STATEMENT -> Extra(if (datedAmountLines >= 4) 2.5 else 0.0, anchors = false)
        // A till slip: a short list of amounts with a total, whatever words the shop prints.
        DocumentType.RECEIPT -> if (amountLines >= 3 && totalLine && chars < TILL_SLIP_CHARS && datedAmountLines < 4) {
            Extra(1.5, anchors = true)
        } else {
            Extra(0.0, anchors = false)
        }
        DocumentType.ID_CARD -> {
            var points = 0.0
            // Passports and many ID cards carry a machine-readable zone ("P<PAK...<<").
            if (mrzLines > 0) points += 4.0
            // A national identity number (12345-1234567-1) on a card-sized text.
            val cardNumber = idNumbers > 0 && shortText
            if (cardNumber) points += 2.5
            if (shortText) points += 0.5
            // A card never has blanks to fill in: those are forms asking for the card's details.
            if (blankLabels >= 5) points -= 3.0
            Extra(points, anchors = mrzLines > 0 || cardNumber)
        }
        else -> Extra(0.0, anchors = false)
    }

    companion object {
        private val FUNCTION_WORDS = setOf(
            "the", "and", "to", "of", "we", "you", "our", "is", "that", "for", "in", "this", "with", "be", "will",
            "are", "your", "have", "it", "as", "on", "at", "by", "from", "has", "been", "would", "please", "i",
        )
        private val AMOUNT = Regex("\\d[\\d,]*[.,]\\d{2}(?!\\d)|(?<![\\d,])\\d{1,3}(?:,\\d{3})+(?![\\d,])")
        private val DATE_LIKE = Regex("\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}|\\d{1,2}[\\s-]?(?i:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)")

        /** "Name:" with nothing after it, "Name: ______", "Age ...... Gender:" — not "Tea ...... 50.00". */
        private val BLANK_LABEL = Regex("^[\\p{L} ./'()&,-]{2,40}:\\s*$|_{3,}\\s*$|_{3,}\\s+\\p{L}|\\.{5,}\\s*$|\\.{5,}\\s+\\p{L}")
        private val MRZ = Regex("[A-Z0-9<]{5,}<<[A-Z0-9<]{3,}")
        private val ID_NUMBER = Regex("(?<!\\d)\\d{5}-\\d{7}-\\d(?!\\d)")
        private val TOTALS = listOf("total", "grand total", "net total", "balance due", "amount due", "net amount", "to pay")
            .map(MatchText::fold)
        private val WORD = Regex("\\p{L}+")

        fun of(lines: List<String>): Layout {
            var functionWords = 0
            for (line in lines) {
                for (word in WORD.findAll(line)) if (word.value.lowercase() in FUNCTION_WORDS) functionWords++
            }
            return Layout(
                functionWords = functionWords,
                blankLabels = lines.count { BLANK_LABEL.containsMatchIn(it) },
                datedAmountLines = lines.count { DATE_LIKE.containsMatchIn(it) && AMOUNT.containsMatchIn(it) },
                amountLines = lines.count { AMOUNT.containsMatchIn(it) },
                totalLine = lines.any { line -> AMOUNT.containsMatchIn(line) && MatchText.of(line).let { words -> TOTALS.any(words::contains) } },
                mrzLines = lines.count { MRZ.containsMatchIn(it.replace(" ", "")) },
                idNumbers = lines.count { ID_NUMBER.containsMatchIn(it) },
                chars = lines.sumOf { it.length },
            )
        }

        private const val SHORT_TEXT_CHARS = 600
        private const val TILL_SLIP_CHARS = 900
    }
}
