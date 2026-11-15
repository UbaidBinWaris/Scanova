package app.scanova.android

import app.scanova.android.update.UpdateHost
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.scanova.core.designsystem.component.LoadingState
import app.scanova.feature.auth.ChangePasswordRoute
import app.scanova.feature.auth.SignInRoute
import app.scanova.feature.documents.navigation.LibraryDestination
import app.scanova.feature.documents.navigation.documentsScreens
import app.scanova.feature.documents.navigation.navigateToDocument
import app.scanova.feature.pairing.PairingRoute
import app.scanova.feature.scan.navigation.ScanGraph
import app.scanova.feature.scan.navigation.navigateToScan
import app.scanova.feature.scan.navigation.scanGraph
import app.scanova.feature.settings.navigation.navigateToSettings
import app.scanova.feature.settings.navigation.settingsScreens
import kotlinx.serialization.Serializable

/** Voluntary password change, reached from Settings. */
@Serializable
private data object ChangePasswordDestination

/**
 * The app's top level. Which area is shown follows from stored state (paired?
 * signed in?), see [RootViewModel]; inside the signed-in area a navigation
 * graph connects the features, which do not know about each other.
 */
@Composable
internal fun ScanovaApp(rootViewModel: RootViewModel) {
    val state by rootViewModel.state.collectAsStateWithLifecycle()
    // A pairing link only means something while the phone is unpaired; drop it
    // otherwise so it cannot take effect later, after an unrelated "disconnect".
    LaunchedEffect(state.destination, state.pendingPairingCode) {
        val unpaired = state.destination == RootDestination.PAIRING || state.destination == RootDestination.LOADING
        if (!unpaired && state.pendingPairingCode != null) rootViewModel.onPairingLinkConsumed()
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state.destination) {
            RootDestination.LOADING -> LoadingState()
            RootDestination.PAIRING -> PairingRoute(
                initialPairingCode = state.pendingPairingCode,
                onInitialPairingCodeConsumed = rootViewModel::onPairingLinkConsumed,
            )
            RootDestination.SIGN_IN -> SignInRoute()
            RootDestination.PASSWORD_CHANGE -> ChangePasswordRoute(forced = true, onDone = null)
            RootDestination.MAIN -> {
                MainNavHost()
                // Checks the paired server for a newer build; shows nothing otherwise.
                UpdateHost()
            }
            RootDestination.VAULT_PROBLEM -> VaultProblemScreen(
                keysLost = state.vaultKeysLost,
                onRetry = rootViewModel::onVaultRetry,
                onReset = rootViewModel::onVaultReset,
            )
        }
    }
}

@Composable
private fun MainNavHost() {
    val navController = rememberNavController()
    val leaveScan: () -> Unit = { navController.popBackStack<ScanGraph>(inclusive = true) }

    NavHost(navController = navController, startDestination = LibraryDestination) {
        documentsScreens(
            navController = navController,
            onScan = { navController.navigateToScan() },
            onOpenSettings = { navController.navigateToSettings() },
        )
        scanGraph(
            navController = navController,
            onDocumentSaved = { documentId ->
                // Back in the library, with the new document opened on top of it.
                leaveScan()
                navController.navigateToDocument(documentId)
            },
            onExit = leaveScan,
        )
        settingsScreens(
            navController = navController,
            onChangePassword = { navController.navigate(ChangePasswordDestination) },
        )
        composable<ChangePasswordDestination> {
            ChangePasswordRoute(forced = false, onDone = { navController.popBackStack() })
        }
    }
}
