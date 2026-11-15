package app.scanova.core.data.model

import androidx.annotation.Keep

/**
 * The tools a person can start from Home's quick actions or the Tools tab.
 * Feature modules never depend on each other, so this shared list is how one
 * screen asks for a tool another feature provides; the app module maps each
 * value to the screen that does it.
 *
 * Kept from minification: it is an argument of a type-safe navigation route,
 * which finds its serializer by reflection in release builds.
 */
@Keep
enum class AppTool {
    /** Pick photos from the gallery and make a document of them. */
    IMPORT_IMAGES,

    /** Pick PDF files and add them to the library (editable page by page). */
    IMPORT_FILES,

    /** Scan both sides of an ID card onto one page. */
    ID_CARD,

    /** Copy, share or export the text of a document. */
    EXTRACT_TEXT,

    /** Combine several documents into one. */
    MERGE_PDF,

    /** Split a document into several, or take some pages out. */
    SPLIT_PDF,
}
