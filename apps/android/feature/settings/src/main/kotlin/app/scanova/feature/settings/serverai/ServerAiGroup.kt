package app.scanova.feature.settings.serverai

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.common.result.ApiErrorCode
import app.scanova.core.common.result.AppError
import app.scanova.core.data.serverai.ServerAiStatus
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.message
import app.scanova.core.designsystem.theme.Accent
import app.scanova.core.designsystem.theme.IconSizes
import app.scanova.core.designsystem.theme.ScanovaThemeInfo
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.settings.NoteRow
import app.scanova.feature.settings.R
import app.scanova.feature.settings.RowDivider
import app.scanova.feature.settings.SettingsGroup
import app.scanova.feature.settings.ToggleRow

/**
 * "Server AI" on the Me tab, for the server's owner only: loads the AI model
 * into the server's memory and takes it out again. Nothing at all for other
 * accounts, or before the server has answered.
 */
@Composable
internal fun ServerAiGroup(viewModel: ServerAiViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val status = state.status ?: return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        state.error?.let { error -> MessageBanner(text = errorText(error, status)) }
        SettingsGroup(title = stringResource(R.string.feature_settings_section_server_ai)) {
            val line = status.line()
            ToggleRow(
                title = stringResource(R.string.feature_settings_server_ai_title),
                supporting = stringResource(line.text),
                icon = Icons.Rounded.Memory,
                iconTint = Accent.EMERALD.content(ScanovaThemeInfo.isDark),
                checked = status.on,
                enabled = status.switchEnabled(state.switching),
                onCheckedChange = viewModel::onSwitch,
            )
            RowDivider()
            StatusRow(line)
            RowDivider()
            NoteRow(
                icon = Icons.Rounded.Timer,
                text = stringResource(R.string.feature_settings_server_ai_auto_off, status.autoOffMinutes) +
                    memoryNote(status),
            )
        }
    }
}

/** The state again, with an icon per kind, announced when it changes (Loading → Ready). */
@Composable
private fun StatusRow(line: StatusLine) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.md)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (line.tone) {
            StatusTone.BUSY -> CircularProgressIndicator(
                modifier = Modifier.size(IconSizes.inRowTile),
                strokeWidth = 2.dp,
            )
            StatusTone.READY -> Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = Accent.EMERALD.content(ScanovaThemeInfo.isDark),
                modifier = Modifier.size(IconSizes.inRowTile),
            )
            StatusTone.OFF -> Icon(
                imageVector = Icons.Rounded.PowerSettingsNew,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(IconSizes.inRowTile),
            )
            StatusTone.PROBLEM -> Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = colors.error,
                modifier = Modifier.size(IconSizes.inRowTile),
            )
        }
        Text(text = stringResource(line.text), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
    }
}

/** How much the server has free and how much loading needs, when it said so. */
@Composable
private fun memoryNote(status: ServerAiStatus): String {
    val context = LocalContext.current
    val free = status.availableBytes ?: return ""
    val needed = status.neededBytes
    return if (!status.on && needed != null) {
        " " + stringResource(
            R.string.feature_settings_server_ai_memory_needed,
            Formatter.formatShortFileSize(context, free),
            Formatter.formatShortFileSize(context, needed),
        )
    } else {
        " " + stringResource(R.string.feature_settings_server_ai_memory_free, Formatter.formatShortFileSize(context, free))
    }
}

@Composable
private fun errorText(error: AppError, status: ServerAiStatus): String {
    val context = LocalContext.current
    val free = status.availableBytes
    val needed = status.neededBytes
    return if (error is AppError.Api && error.code == ApiErrorCode.NOT_ENOUGH_MEMORY && free != null && needed != null) {
        stringResource(
            R.string.feature_settings_server_ai_not_enough_memory,
            Formatter.formatShortFileSize(context, needed),
            Formatter.formatShortFileSize(context, free),
        )
    } else {
        error.message()
    }
}
