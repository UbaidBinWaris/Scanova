package app.scanova.feature.settings.me

import app.scanova.core.data.backup.BackupStatus
import app.scanova.core.data.model.AuthState
import app.scanova.core.data.model.User
import app.scanova.core.data.settings.AppSettings
import app.scanova.core.data.settings.ThemeMode
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeStateTest {

    private val last = Instant.parse("2026-11-15T10:00:00Z")
    private val user = User(
        id = "u1",
        email = "ayesha@example.com",
        name = "ayesha khan",
        mustChangePassword = false,
        storageUsedBytes = 1_800,
        storageQuotaBytes = 2_000,
    )
    private val settings = AppSettings()

    @Test
    fun `signed out counts the documents that exist only on this phone`() {
        val state = meUiState(AuthState.SignedOut, BackupStatus(enabled = false, pendingCount = 3, lastBackupAt = null), settings)
        assertFalse(state.isLoading)
        assertFalse(state.signedIn)
        assertEquals(MeAccount.SignedOut(localOnlyCount = 3), state.account)
        // No backup card without an account: the account card explains it.
        assertEquals(MeBackup.Off, state.backup)
    }

    @Test
    fun `signed in shows the name, its initial and the storage against the quota`() {
        val state = meUiState(AuthState.SignedIn(user), BackupStatus(enabled = true, pendingCount = 0, lastBackupAt = last), settings)
        val account = state.account as MeAccount.SignedIn
        assertEquals("ayesha khan", account.displayName)
        assertEquals("A", account.initial)
        assertEquals("ayesha@example.com", account.email)
        assertEquals(0.9f, account.storage.fraction!!, 0.0001f)
        assertTrue(account.storage.nearlyFull)
        assertEquals(MeBackup.UpToDate(last), state.backup)
    }

    @Test
    fun `an account without a name goes by its e-mail address`() {
        val nameless = user.copy(name = "  ")
        val account = meUiState(AuthState.SignedIn(nameless), BackupStatus(true, 0, null), settings).account as MeAccount.SignedIn
        assertEquals("ayesha@example.com", account.displayName)
        assertEquals("A", account.initial)
    }

    @Test
    fun `no quota means no meter`() {
        val unlimited = user.copy(storageQuotaBytes = null)
        val account = meUiState(AuthState.SignedIn(unlimited), BackupStatus(true, 0, null), settings).account as MeAccount.SignedIn
        assertNull(account.storage.fraction)
        assertFalse(account.storage.nearlyFull)
    }

    @Test
    fun `waiting documents are counted on the backup card`() {
        val state = meUiState(AuthState.SignedIn(user), BackupStatus(enabled = true, pendingCount = 2, lastBackupAt = last), settings)
        assertEquals(MeBackup.Waiting(count = 2, lastBackupAt = last), state.backup)
    }

    @Test
    fun `a temporary password still counts as signed in`() {
        val state = meUiState(AuthState.PasswordChangeRequired(user), BackupStatus(true, 0, null), settings)
        assertTrue(state.signedIn)
        assertEquals(MeBackup.UpToDate(null), state.backup)
    }

    @Test
    fun `appearance, scanning and lock come from the settings`() {
        val chosen = AppSettings(themeMode = ThemeMode.DARK, autoCaptureEnabled = true, autoEnhance = false, appLockEnabled = true)
        val state = meUiState(AuthState.SignedOut, BackupStatus(false, 0, null), chosen)
        assertEquals(ThemeMode.DARK, state.themeMode)
        assertTrue(state.autoCaptureEnabled)
        assertFalse(state.autoEnhanceEnabled)
        assertTrue(state.appLockEnabled)
    }

    @Test
    fun `screenshots are allowed by default`() {
        val state = meUiState(AuthState.SignedOut, BackupStatus(false, 0, null), AppSettings())
        assertFalse(state.blockScreenshots)
        assertFalse(state.screenshotsBlocked)
        assertFalse(state.screenshotSettingLockedByAppLock)
    }

    @Test
    fun `blocking screenshots comes from the settings and leaves the switch free`() {
        val state = meUiState(AuthState.SignedOut, BackupStatus(false, 0, null), AppSettings(blockScreenshots = true))
        assertTrue(state.blockScreenshots)
        assertTrue(state.screenshotsBlocked)
        assertFalse(state.screenshotSettingLockedByAppLock)
    }

    @Test
    fun `the app lock blocks screenshots and locks the switch on`() {
        val locked = AppSettings(appLockEnabled = true, blockScreenshots = false)
        val state = meUiState(AuthState.SignedOut, BackupStatus(false, 0, null), locked)
        assertFalse(state.blockScreenshots)
        assertTrue(state.screenshotsBlocked)
        assertTrue(state.screenshotSettingLockedByAppLock)
        // The user's own choice is kept: with the lock off again, screenshots follow it.
        val unlocked = meUiState(AuthState.SignedOut, BackupStatus(false, 0, null), locked.copy(appLockEnabled = false))
        assertFalse(unlocked.screenshotsBlocked)
        assertFalse(unlocked.screenshotSettingLockedByAppLock)
    }

    @Test
    fun `auto-capture is off unless chosen`() {
        val state = meUiState(AuthState.SignedOut, BackupStatus(false, 0, null), AppSettings())
        assertFalse(state.autoCaptureEnabled)
        assertEquals(ThemeMode.SYSTEM, state.themeMode)
    }
}
