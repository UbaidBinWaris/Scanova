package app.scanova.feature.tools.common

import android.content.Context
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.scanova.core.data.model.Document
import app.scanova.feature.tools.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A date as the phone's language writes it, e.g. "16 Nov 2026". */
internal fun formatDate(instant: Instant): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(instant.atZone(ZoneId.systemDefault()))

/** "3 pages · 16 Nov 2026" */
@Composable
internal fun documentDetails(document: Document): String = stringResource(
    R.string.feature_tools_document_details,
    pluralStringResource(R.plurals.feature_tools_page_count, document.pageCount, document.pageCount),
    formatDate(document.capturedAt),
)

/** A file size as the phone writes it, e.g. "2.4 MB". */
@Composable
internal fun formatSize(bytes: Long): String = formatSize(LocalContext.current, bytes)

internal fun formatSize(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0))
