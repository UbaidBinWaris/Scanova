package app.scanova.core.ml.text

/**
 * Frequent English words plus the vocabulary of everyday documents (receipts,
 * invoices, bills, letters, forms, statements). Used only as evidence that
 * recognised text is real text — upside-down or sideways text read as if it
 * were upright gives letters that rarely form these words. It is not a
 * spelling dictionary and does not need to be complete.
 */
internal object CommonWords {
    fun contains(word: String): Boolean = word.lowercase() in WORDS

    private val WORDS: Set<String> = """
        a about above after again against all also am an and any are as at be because been before being below
        between both but by can could did do does doing down during each few for from further had has have
        having he her here hers him his how if in into is it its just me more most my no nor not now of off
        on once only or other our ours out over own same she should so some such than that the their them
        then there these they this those through to too under until up very was we were what when where
        which while who whom why will with would you your yours

        one two three four five six seven eight nine ten first second third last next new old good best great
        high low long short small large big full total part day days week weeks month months year years time
        date today name names number numbers address phone email mobile city country state street road town
        area house home office company business service services customer customers client account accounts
        page pages copy original note notes please thank thanks dear sir madam mr mrs ms miss dr yours
        sincerely faithfully regards kind truly best wishes subject reference ref enclosed attached find
        letter letters inform informed pleased hereby certify certified certificate certificates award awarded
        presented recognition achievement completion completed successfully participation appreciation course
        program programme training school college university student students class grade degree diploma
        director principal manager head chairman president secretary signature signed authorised authorized

        receipt receipts invoice invoices bill bills billing payment payments paid pay payable due amount
        amounts balance price prices cost costs charge charges fee fees tax taxes vat gst sales sale sub
        subtotal discount net gross cash card credit debit change tendered cashier store shop market mart
        item items qty quantity unit units rate description order orders delivery shipping ship sold buyer
        seller vendor supplier terms conditions issued issue valid until expiry expires bank branch statement
        opening closing deposit deposits withdrawal withdrawals transfer transaction transactions available
        period interest loan electricity gas water meter reading consumer connection tariff usage previous
        current present surcharge late arrears customer

        form forms application apply applicant registration register details information personal father
        mother husband wife gender male female birth age nationality religion marital status occupation
        education qualification experience declaration declare tick fill block letters capital office use only
        identity national card id holder issued expiry passport licence license driving permit

        patient doctor clinic hospital medical medicine prescription tablet tablets tab capsule syrup dose
        daily twice morning evening night after before meal meals food follow review diagnosis test tests
        report pharmacy

        ticket tickets passenger passengers flight flights boarding pass gate seat seats departure arrival
        depart arrive journey train bus coach platform terminal class economy booking fare travel trip airline
        airport station event venue admit entry show hall row

        welcome visit again shopping purchase purchased come thank you have nice
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotBlank() }.toHashSet()
}
