package app.scanova.feature.settings.me

import app.scanova.core.data.backup.BackupStatus
import app.scanova.core.data.model.AuthState
import app.scanova.core.data.settings.AppSettings
import app.scanova.core.data.settings.ThemeMode
import app.scanova.feature.settings.STORAGE_WARNING_FRACTION
import app.scanova.feature.settings.initialOf
import app.scanova.feature.settings.storageFraction
import java.time.Instant

/** Space used in the account against its quota. */
internal data class StorageUse(val usedBytes: Long, val quotaBytes: Long?) {
    /** 0..1 of the quota, or null without one (the meter then shows the amount only). */
    val fraction: Float? get() = storageFraction(usedBytes, quotaBytes)

    val nearlyFull: Boolean get() = fraction?.let { it >= STORAGE_WARNING_FRACTION } == true
}

/** Who is using the app. */
internal sealed interface MeAccount {
    /** No account: everything works, [localOnlyCount] documents exist only on this phone. */
    data class SignedOut(val localOnlyCount: Int) : MeAccount

    data class SignedIn(
        /** The name, or the e-mail address for an account without one. */
        val displayName: String,
        val email: String,
        val initial: String,
        val storage: StorageUse,
    ) : MeAccount
}

/** The backup card, shown while signed in. */
internal sealed interface MeBackup {
    data object Off : MeBackup

    /** Nothing waiting. [lastBackupAt] is null until a backup has finished. */
    data class UpToDate(val lastBackupAt: Instant?) : MeBackup

    data class Waiting(val count: Int, val lastBackupAt: Instant?) : MeBackup
}

/** One-off confirmations shown as a snackbar. */
internal enum class MeNotice { BACKUP_STARTED }

internal data class MeUiState(
    /** True until the account and settings are first known. */
    val isLoading: Boolean = true,
    val account: MeAccount = MeAccount.SignedOut(localOnlyCount = 0),
    val backup: MeBackup = MeBackup.Off,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val autoCaptureEnabled: Boolean = false,
    val autoEnhanceEnabled: Boolean = true,
    val appLockEnabled: Boolean = false,
    /** The last change could not be stored. */
    val saveFailed: Boolean = false,
    val notice: MeNotice? = null,
) {
    val signedIn: Boolean get() = account is MeAccount.SignedIn
}

/**
 * Folds the account, the backup status and the settings into what the Me tab
 * shows. A temporary password still counts as signed in: the account exists.
 */
internal fun meUiState(auth: AuthState, backup: BackupStatus, settings: AppSettings): MeUiState {
    val user = when (auth) {
        is AuthState.SignedIn -> auth.user
        is AuthState.PasswordChangeRequired -> auth.user
        AuthState.SignedOut -> null
    }
    val account = if (user == null) {
        MeAccount.SignedOut(localOnlyCount = backup.pendingCount.coerceAtLeast(0))
    } else {
        val displayName = user.name.trim().ifEmpty { user.email }
        MeAccount.SignedIn(
            displayName = displayName,
            email = user.email,
            initial = initialOf(displayName),
            storage = StorageUse(user.storageUsedBytes, user.storageQuotaBytes),
        )
    }
    val backupCard = when {
        user == null || !backup.enabled -> MeBackup.Off
        backup.pendingCount > 0 -> MeBackup.Waiting(backup.pendingCount, backup.lastBackupAt)
        else -> MeBackup.UpToDate(backup.lastBackupAt)
    }
    return MeUiState(
        isLoading = false,
        account = account,
        backup = backupCard,
        themeMode = settings.themeMode,
        autoCaptureEnabled = settings.autoCaptureEnabled,
        autoEnhanceEnabled = settings.autoEnhance,
        appLockEnabled = settings.appLockEnabled,
    )
}
