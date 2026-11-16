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
import androidx.compose.material.icons.rounded.Info
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import app.scanova.core.data.serverai.ServerAiGroup
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
 * "Server AI" on the Me tab, for the server's owner only: loads the AI models
 * into the server's memory and takes them out again. The text AI switch is
 * always there for an owner; "Page clean-up" only when the server has that
 * switch. Nothing at all for other accounts, or before the server has answered.
 */
@Composable
internal fun ServerAiGroup(viewModel: ServerAiViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val status = state.status ?: return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        state.error?.let { failure -> MessageBanner(text = errorText(failure.error, state.memoryShortfall())) }
        SettingsGroup(title = stringResource(R.string.feature_settings_section_server_ai)) {
            SwitchRows(
                title = stringResource(R.string.feature_settings_server_ai_title),
                icon = Icons.Rounded.Memory,
                iconTint = Accent.EMERALD.content(ScanovaThemeInfo.isDark),
                status = status,
                switching = state.isSwitching(ServerAiGroup.TEXT),
                onSwitch = { on -> viewModel.onSwitch(ServerAiGroup.TEXT, on) },
            )
            state.cleanup?.let { cleanup ->
                // A separate switch with its own model and memory: a full-width line between the two.
                RowDivider(indented = false)
                SwitchRows(
                    title = stringResource(R.string.feature_settings_server_ai_cleanup_title),
                    icon = Icons.Rounded.AutoFixHigh,
                    iconTint = Accent.AMBER.content(ScanovaThemeInfo.isDark),
                    status = cleanup,
                    switching = state.isSwitching(ServerAiGroup.IMAGE),
                    about = stringResource(R.string.feature_settings_server_ai_cleanup_about),
                    onSwitch = { on -> viewModel.onSwitch(ServerAiGroup.IMAGE, on) },
                )
            }
        }
    }
}

/**
 * One switch's rows: the switch itself (the whole row is the target), its state
 * with an icon, what it is for when [about] says so, and when it turns itself
 * off with the server's memory.
 */
@Composable
private fun SwitchRows(
    title: String,
    icon: ImageVector,
    iconTint: Color,
    status: ServerAiStatus,
    switching: Boolean,
    onSwitch: (Boolean) -> Unit,
    about: String? = null,
) {
    val line = status.line()
    ToggleRow(
        title = title,
        supporting = stringResource(line.text),
        icon = icon,
        iconTint = iconTint,
        checked = status.on,
        enabled = status.switchEnabled(switching),
        onCheckedChange = onSwitch,
    )
    RowDivider()
    StatusRow(title, line)
    if (about != null) {
        RowDivider()
        NoteRow(icon = Icons.Rounded.Info, text = about)
    }
    RowDivider()
    NoteRow(
        icon = Icons.Rounded.Timer,
        text = pluralStringResource(R.plurals.feature_settings_server_ai_auto_off, status.autoOffMinutes, status.autoOffMinutes) +
            memoryNote(status),
    )
}

/**
 * The state again, with an icon per kind, announced when it changes (Loading → Ready).
 * The announcement names the switch ([title]), as there can be two of them.
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
