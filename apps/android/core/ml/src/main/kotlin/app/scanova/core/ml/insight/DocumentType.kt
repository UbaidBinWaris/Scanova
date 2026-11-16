package app.scanova.core.ml.insight

/**
 * The kinds of document the phone recognises from their text. A recognised
 * type is stored as a document tag ([tag]), so it is searchable, shown in the
 * library and backed up with the other metadata. The tag text is a stable,
 * English value: renaming one would leave older documents with the old tag.
 */
enum class DocumentType(val id: String, val tag: String?) {
    RECEIPT("receipt", "Receipt"),
    INVOICE("invoice", "Invoice"),
    BILL("bill", "Bill"),
    ID_CARD("id_card", "ID card"),
    CERTIFICATE("certificate", "Certificate"),
    LETTER("letter", "Letter"),
    FORM("form", "Form"),
    BANK_STATEMENT("bank_statement", "Bank statement"),
    PRESCRIPTION("prescription", "Prescription"),
    TICKET("ticket", "Ticket"),

    /** Nothing recognisable: no tag, the title comes from the heading. */
    OTHER("other", null),
    ;

    companion object {
        private val byTag: Map<String, DocumentType> =
            entries.mapNotNull { type -> type.tag?.let { it.lowercase() to type } }.toMap()

        /** The type a tag stands for (ignoring case), or null for any other tag. */
        fun fromTag(tag: String): DocumentType? = byTag[tag.trim().lowercase()]

        /** The first of [tags] that names a type, or null. */
        fun fromTags(tags: List<String>): DocumentType? = tags.firstNotNullOfOrNull(::fromTag)
    }
}
