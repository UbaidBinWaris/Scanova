package app.scanova.core.data.export

/** How many files an export makes and roughly how big they are, before anything is written. */
data class ExportSummary(
    val fileCount: Int,
    val pageCount: Int,
    /** Approximate total size in bytes (exact for stored PDFs and text). */
    val estimatedBytes: Long,
)

/**
 * Estimates an export from what is known without decoding anything: stored
 * PDF sizes, page dimensions and typical JPEG compression of document pages.
 * Good enough for "about 3 MB"; the real files can differ by a fair margin.
 */
object ExportEstimate {
    /** A4 portrait in points: the size assumed for pages only known from a PDF. */
    private const val A4_WIDTH_POINTS = 595
    private const val A4_HEIGHT_POINTS = 842

    /** Typical bytes per pixel of a document page as JPEG: stored pages (quality 85) and each export quality. */
    private const val STORED_BYTES_PER_PIXEL = 0.20
    private const val HIGH_BYTES_PER_PIXEL = 0.20
    private const val MEDIUM_BYTES_PER_PIXEL = 0.11

    /** A heading like "Page 12" plus blank lines, per page section of a text file. */
    private const val TEXT_HEADING_BYTES = 12

    fun summarize(format: ExportFormat, quality: ImageQuality, sources: List<ExportSource>): ExportSummary {
        val pages = sources.sumOf { it.pageCount }
        return when (format) {
            ExportFormat.PDF -> ExportSummary(
                fileCount = sources.size,
                pageCount = pages,
                estimatedBytes = sources.sumOf { source ->
                    source.pdfSizeBytes ?: pageSizes(source, quality).sumOf { bytes(it, STORED_BYTES_PER_PIXEL) }
                },
            )
            ExportFormat.IMAGES -> ExportSummary(
                fileCount = pages,
                pageCount = pages,
                estimatedBytes = sources.sumOf { source ->
                    pageSizes(source, quality).sumOf { size -> imageBytes(source, size, quality) }
                },
            )
            ExportFormat.LONG_IMAGE -> {
                val plans = sources.map { source ->
                    LongImageLayout.plan(pageSizes(source, quality), maxWidth = quality.longImageWidth)
                }
                ExportSummary(
                    fileCount = plans.sumOf { it.size },
                    pageCount = pages,
                    estimatedBytes = plans.sumOf { parts ->
                        // White gaps cost next to nothing; the pages are what is paid for.
                        parts.sumOf { part ->
                            part.placements.sumOf { bytes(PixelSize(it.width, it.height), bytesPerPixel(quality)) }
                        }
                    },
                )
            }
            ExportFormat.TEXT -> {
                val withText = sources.filterNot { it.text.isEmpty }
                ExportSummary(
                    fileCount = withText.size,
                    pageCount = pages,
                    estimatedBytes = withText.sumOf { source ->
                        source.text.sections().sumOf { section ->
                            section.text.toByteArray(Charsets.UTF_8).size.toLong() + TEXT_HEADING_BYTES
                        }
                    },
                )
            }
        }
    }

    /** The page sizes an export will work with: stored images, or the PDF drawn at the quality's resolution. */
    internal fun pageSizes(source: ExportSource, quality: ImageQuality): List<PixelSize> =
        source.pageSizes.ifEmpty {
            val rendered = PdfRenderSizing.pixelSize(
                A4_WIDTH_POINTS,
                A4_HEIGHT_POINTS,
                quality.renderDpi,
                quality.pageLongEdge ?: PdfRenderSizing.EXPORT_MAX_LONG_EDGE,
            )
            List(source.pageCount.coerceAtLeast(0)) { rendered }
        }

    private fun imageBytes(source: ExportSource, size: PixelSize, quality: ImageQuality): Long {
        val longEdge = quality.pageLongEdge
        return when {
            // Stored page images are shared as they are.
            longEdge == null && source.hasPageImages -> bytes(size, STORED_BYTES_PER_PIXEL)
            longEdge == null -> bytes(size, bytesPerPixel(quality))
            else -> bytes(PdfRenderSizing.fit(size, longEdge), bytesPerPixel(quality))
        }
    }

    private fun bytesPerPixel(quality: ImageQuality): Double = when (quality) {
        ImageQuality.HIGH -> HIGH_BYTES_PER_PIXEL
        ImageQuality.MEDIUM -> MEDIUM_BYTES_PER_PIXEL
    }

    private fun bytes(size: PixelSize, bytesPerPixel: Double): Long =
        (size.width.toLong() * size.height * bytesPerPixel).toLong()
}
