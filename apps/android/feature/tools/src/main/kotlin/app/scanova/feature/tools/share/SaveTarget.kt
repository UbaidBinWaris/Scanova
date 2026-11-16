package app.scanova.feature.tools.share

import app.scanova.core.data.export.ExportFormat
import app.scanova.core.data.export.ExportNames

/** Why the sheet is open: to hand copies to another app, or to save them on this device. */
internal enum class SheetPurpose { SHARE, SAVE }

/**
 * The system's "save as" dialogs, one per kind of file an export writes.
 * They are registered before they are needed (an activity-result rule), so
 * the kinds are fixed: a bundle's MIME type picks one of them.
 */
internal enum class SaveFileKind(val mimeType: String) {
    PDF(ExportFormat.PDF.mimeType),
    JPEG(ExportFormat.IMAGES.mimeType),
    TEXT(ExportFormat.TEXT.mimeType),
    ;

    companion object {
        fun of(mimeType: String): SaveFileKind? = entries.firstOrNull { it.mimeType.equals(mimeType, ignoreCase = true) }
    }
}

/** Where the system asks the user to put an export. */
internal sealed interface SaveTarget {
    /** One file: the "save as" dialog for [kind], suggesting [fileName] (the user may change it). */
    data class OneFile(val kind: SaveFileKind, val fileName: String) : SaveTarget

    /**
     * Several files: the user picks a folder. When [subfolder] is set the
     * files go into a new folder of that name there (the pages of one
     * document stay together instead of spreading over Downloads).
     */
    data class Folder(val subfolder: String?) : SaveTarget

    companion object {
        /**
         * The target for files named [fileNames] of type [mimeType], exported
         * from documents titled [documentTitles]. Null when there is nothing to save.
         */
        fun of(fileNames: List<String>, mimeType: String, documentTitles: List<String>): SaveTarget? {
            if (fileNames.isEmpty()) return null
            val kind = SaveFileKind.of(mimeType)
            if (fileNames.size == 1 && kind != null) return OneFile(kind, fileNames.single())
            val single = documentTitles.singleOrNull()
            return Folder(subfolder = if (fileNames.size > 1 && single != null) ExportNames.baseName(single) else null)
        }
    }
}
