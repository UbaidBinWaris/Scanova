package app.scanova.feature.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.pairing.PairingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PairingUiState(
    val serverUrl: String = "",
    val fingerprint: String = "",
    val serverUrlInvalid: Boolean = false,
    val fingerprintInvalid: Boolean = false,
    val isConnecting: Boolean = false,
    val error: AppError? = null,
    val scannerOpen: Boolean = false,
    /** The last QR code read was not a Scanova pairing code. */
    val scannedCodeRejected: Boolean = false,
    /**
     * A pairing code (scanned, or from a `scanova://pair` link) is waiting for the
     * user to confirm the server. A code never connects on its own: a link can
     * come from any website or app, and pairing decides where the password and
     * every document will be sent.
     */
    val confirmation: PairingConfirmation? = null,
    /** Connected and saved: the route closes itself. */
    val paired: Boolean = false,
)

/** What the user is asked to confirm before a pairing code is used. */
data class PairingConfirmation(
    /** Host and, when not 443, port. Shown instead of any name the server reports. */
    val authority: String,
    /** True when the code pins the server's CA by fingerprint. */
    val pinned: Boolean,
)

/**
 * State holder for the pairing screen. When pairing succeeds the paired server
 * is stored, which the app's root observes; this screen does not navigate.
 */
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val pairingRepository: PairingRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(PairingUiState())
    val state: StateFlow<PairingUiState> = mutableState.asStateFlow()

    fun onServerUrlChange(value: String) {
        mutableState.update { it.copy(serverUrl = value, serverUrlInvalid = false, error = null) }
    }

    fun onFingerprintChange(value: String) {
        mutableState.update { it.copy(fingerprint = value, fingerprintInvalid = false, error = null) }
    }

    fun onOpenScanner() {
        mutableState.update { it.copy(scannerOpen = true, scannedCodeRejected = false, error = null) }
    }

    fun onCloseScanner() {
        mutableState.update { it.copy(scannerOpen = false, scannedCodeRejected = false) }
    }

    /** A QR code was read by the camera, or the app was opened from a `scanova://pair` link. */
    fun onPairingCode(text: String) {
        val code = pairingRepository.parsePairingCode(text)
        if (code == null) {
            mutableState.update { it.copy(scannedCodeRejected = true) }
            return
        }
        mutableState.update {
            it.copy(
                serverUrl = code.serverUrl,
                fingerprint = code.caFingerprint.orEmpty(),
                serverUrlInvalid = false,
                fingerprintInvalid = false,
                scannerOpen = false,
                scannedCodeRejected = false,
                error = null,
                confirmation = PairingConfirmation(
                    authority = authorityOf(code.serverUrl),
                    pinned = !code.caFingerprint.isNullOrBlank(),
                ),
            )
        }
    }

    /** The user declined the server: the details stay in the form to review or edit. */
    fun onConfirmationDismissed() {
        mutableState.update { it.copy(confirmation = null) }
    }

    /** The user accepted the server. The screen then connects (after any permission request). */
    fun onConfirmationAccepted() {
        mutableState.update { it.copy(confirmation = null) }
    }

    fun onConnect() {
        val current = mutableState.value
        if (current.isConnecting) return
        val url = current.serverUrl.trim()
        val fingerprint = current.fingerprint.trim()
        val urlInvalid = !pairingRepository.isValidServerUrl(url)
        val fingerprintInvalid = fingerprint.isNotEmpty() && !pairingRepository.isValidFingerprint(fingerprint)
        if (urlInvalid || fingerprintInvalid) {
            mutableState.update { it.copy(serverUrlInvalid = urlInvalid, fingerprintInvalid = fingerprintInvalid) }
            return
        }
        mutableState.update { it.copy(isConnecting = true, error = null) }
        viewModelScope.launch {
            val result = pairingRepository.pair(url, fingerprint.ifEmpty { null })
            mutableState.update {
                it.copy(
                    isConnecting = false,
                    error = (result as? AppResult.Failure)?.error,
                    paired = result is AppResult.Success,
                )
            }
        }
    }
}

/** `host` or `host:port` of a server URL; the raw text if it cannot be parsed. */
internal fun authorityOf(serverUrl: String): String {
    val uri = runCatching { java.net.URI(serverUrl.trim()) }.getOrNull()
    val host = uri?.host ?: return serverUrl.trim()
    return if (uri.port == -1 || uri.port == 443) host else "$host:${uri.port}"
}
