package app.scanova.core.data.auth

import android.content.Context
import android.os.Build
import android.provider.Settings
import app.scanova.core.network.api.DeviceDto

/** Describes this phone for the server's list of signed-in devices. */
internal class DeviceInfoProvider(
    private val context: Context,
) {
    fun current(): DeviceDto {
        val model = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { !it.isNullOrBlank() }
            .joinToString(" ")
            .ifBlank { "Android" }
        // The name the owner gave the phone ("Sara's Pixel"), when the system exposes it.
        val name = runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: model
        val appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        return DeviceDto(
            name = name.take(MAX_LENGTH),
            model = model.take(MAX_LENGTH),
            appVersion = appVersion.take(MAX_LENGTH),
        )
    }

    private companion object {
        const val MAX_LENGTH = 100
    }
}
