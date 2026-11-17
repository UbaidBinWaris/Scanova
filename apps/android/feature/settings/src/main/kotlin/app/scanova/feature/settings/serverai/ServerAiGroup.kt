package app.scanova.feature.settings.serverai

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
 * "Server AI" on the Me tab, for the server's owner only: the "Page clean-up"
 * switch, which loads the clean-up model into the server's memory and takes it
 * out again. Nothing at all for other accounts, before the server has answered,
 * or on a server without page clean-up. (The server's text AI is not used and
 * has no switch.)
 */
@Composable
internal fun ServerAiGroup(viewModel: ServerAiViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cleanup = state.cleanup ?: return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        state.error?.let { error -> MessageBanner(text = errorText(error, state.memoryShortfall())) }
        SettingsGroup(title = stringResource(R.string.feature_settings_section_server_ai)) {
            val title = stringResource(R.string.feature_settings_server_ai_cleanup_title)
            // The whole row is the target; it says what the switch is for, the row below its state.
            ToggleRow(
                title = title,
                supporting = stringResource(R.string.feature_settings_server_ai_cleanup_about),
                icon = Icons.Rounded.AutoFixHigh,
                iconTint = Accent.AMBER.content(ScanovaThemeInfo.isDark),
                checked = cleanup.on,
                enabled = cleanup.switchEnabled(state.switching),
                onCheckedChange = viewModel::onSwitch,
            )
            RowDivider()
            StatusRow(title, cleanup.line())
            if (cleanup.isSetUp || cleanup.on) {
                RowDivider()
                NoteRow(
                    icon = Icons.Rounded.Timer,
                    text = pluralStringResource(
                        R.plurals.feature_settings_server_ai_auto_off,
                        cleanup.autoOffMinutes,
                        cleanup.autoOffMinutes,
                    ) + memoryNote(cleanup),
                )
            }
        }
    }
}

/**
 * The state again, with an icon per kind, announced when it changes (Loading → Ready).
 * The announcement names the switch ([title]).
 */
@Composable
private fun StatusRow(title: String, line: StatusLine) {
    val colors = MaterialTheme.colorScheme
    val announced = stringResource(R.string.feature_settings_server_ai_status_named, title, stringResource(line.text))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.md)
            .clearAndSetSemantics {
                contentDescription = announced
                liveRegion = LiveRegionMode.Polite
            },
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

/** The memory message with the flipped switch's numbers ([shortfall] is needed to free), else the general one. */
@Composable
private fun errorText(error: AppError, shortfall: Pair<Long, Long>?): String {
    val context = LocalContext.current
    val (needed, free) = shortfall ?: return error.message()
    return stringResource(
        R.string.feature_settings_server_ai_not_enough_memory,
        Formatter.formatShortFileSize(context, needed),
        Formatter.formatShortFileSize(context, free),
    )
}
