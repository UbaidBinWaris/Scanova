package app.scanova.feature.scan.importer

/** Why a picked photo did not become a page. */
internal enum class PhotoProblemKind {
    /** Not a picture this phone can open, damaged, or no longer available. */
    UNREADABLE,

    /** Too large for this phone's memory. */
    TOO_LARGE,

    /** The encrypted store could not take it (usually: the phone is full). */
    STORAGE,
}

internal data class PhotoProblem(val fileName: String?, val kind: PhotoProblemKind)

/** Problems of one kind, for a short summary: the file names that are known, plus how many have none. */
internal data class PhotoProblemGroup(val kind: PhotoProblemKind, val names: List<String>, val unnamed: Int) {
    val count: Int get() = names.size + unnamed
}

/**
 * The photos one import works through: in the order they were picked,
 * each once, at most [limit] of them (the rest are reported, not dropped
 * silently).
 */
internal data class PhotoImportPlan(val photos: List<String>, val overLimit: Int) {
    companion object {
        /** Photos taken in one go. A scan keeps a small preview of every page in memory. */
        const val MAX_PHOTOS = 100

        fun of(picked: List<String>, limit: Int = MAX_PHOTOS): PhotoImportPlan {
            val distinct = picked.filter { it.isNotBlank() }.distinct()
            return PhotoImportPlan(photos = distinct.take(limit), overLimit = (distinct.size - limit).coerceAtLeast(0))
        }
    }
}

/** What an import run came to, and so what the screen does next. */
internal sealed interface PhotoImportOutcome {
    /** Every photo is in the scan: straight on to the review. */
    data object AllAdded : PhotoImportOutcome

    /** Some photos are in the scan and some are not: say which before the review. */
    data class SomeAdded(val added: Int, val problems: List<PhotoProblemGroup>, val overLimit: Int) : PhotoImportOutcome

    /** None of the photos could be used. */
    data class NoneAdded(val problems: List<PhotoProblemGroup>) : PhotoImportOutcome

    companion object {
        fun of(added: Int, problems: List<PhotoProblem>, overLimit: Int): PhotoImportOutcome {
            val groups = group(problems)
            return when {
                added == 0 -> NoneAdded(groups)
                groups.isEmpty() && overLimit == 0 -> AllAdded
                else -> SomeAdded(added, groups, overLimit)
            }
        }

        /** Problems grouped by kind (in the order of [PhotoProblemKind]), names in the order the photos were picked. */
        fun group(problems: List<PhotoProblem>): List<PhotoProblemGroup> =
            PhotoProblemKind.entries.mapNotNull { kind ->
                val ofKind = problems.filter { it.kind == kind }
                if (ofKind.isEmpty()) {
                    null
                } else {
                    val names = ofKind.mapNotNull { it.fileName?.takeIf(String::isNotBlank) }
                    PhotoProblemGroup(kind, names, unnamed = ofKind.size - names.size)
                }
            }
    }
}

/** Where the import screen goes when it is left. */
internal enum class PhotoImportExit {
    /** To the review of the scan's pages. */
    REVIEW,

    /** Out of the scan flow: there are no pages to review. */
    CLOSE,
    ;

    companion object {
        /** Leaving with [pagesInScan] pages in the scan (from earlier, or just imported). */
        fun leaving(pagesInScan: Int): PhotoImportExit = if (pagesInScan > 0) REVIEW else CLOSE
    }
}
