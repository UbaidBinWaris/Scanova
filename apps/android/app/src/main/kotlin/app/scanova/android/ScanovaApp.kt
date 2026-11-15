package app.scanova.android

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.scanova.android.update.UpdateHost
import app.scanova.core.data.model.AppTool
import app.scanova.core.designsystem.component.LoadingState
import app.scanova.feature.auth.ChangePasswordRoute
import app.scanova.feature.auth.SignInRoute
import app.scanova.feature.auth.SignUpRoute
import app.scanova.feature.auth.WelcomeRoute
import app.scanova.feature.documents.navigation.documentsScreens
import app.scanova.feature.documents.navigation.navigateToDocument
import app.scanova.feature.pairing.PairingRoute
import app.scanova.feature.scan.navigation.ScanGraph
import app.scanova.feature.scan.navigation.editorScreens
import app.scanova.feature.scan.navigation.navigateToEditor
import app.scanova.feature.scan.navigation.navigateToScan
import app.scanova.feature.scan.navigation.scanGraph
import app.scanova.feature.settings.navigation.navigateToSettings
import app.scanova.feature.settings.navigation.settingsScreens
import app.scanova.feature.tools.navigation.navigateToExtractText
import app.scanova.feature.tools.navigation.navigateToShare
import app.scanova.feature.tools.navigation.navigateToTool
import app.scanova.feature.tools.navigation.toolsScreens
import kotlinx.serialization.Serializable

/** Voluntary password change, reached from Settings. */
@Serializable
private data object ChangePasswordDestination

/** Sign in to back up and sync. */
@Serializable
private data object SignInDestination

/** Create a free account (open sign-up). */
@Serializable
private data object SignUpDestination

/** Use a different server than the built-in one; [pairingCode] from a scanova://pair link. */
@Serializable
private data class ServerDestination(val pairingCode: String? = null)

/**
 * The app's top level. Scanova works on the phone straight away: no server to
 * pick, no account needed. Signing in (or creating an account) is a screen like
 * any other and turns on backup and sync. See [RootViewModel].
 */
@Composable
internal fun ScanovaApp(rootViewModel: RootViewModel) {
    val state by rootViewModel.state.collectAsStateWithLifecycle()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state.destination) {
            RootDestination.LOADING -> LoadingState()
            RootDestination.WELCOME -> WelcomeRoute(
                onGetStarted = { rootViewModel.onWelcomeDone(openSignIn = false) },
                onSignIn = { rootViewModel.onWelcomeDone(openSignIn = true) },
            )
            RootDestination.PASSWORD_CHANGE -> ChangePasswordRoute(forced = true, onDone = null)
            RootDestination.MAIN -> {
                MainNavHost(state, rootViewModel)
                // Checks the server for a newer build; shows nothing otherwise.
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
private fun MainNavHost(state: RootUiState, rootViewModel: RootViewModel) {
    val navController = rememberNavController()
    val leaveScan: () -> Unit = { navController.popBackStack<ScanGraph>(inclusive = true) }

    // "I have an account" on the welcome screen.
    LaunchedEffect(state.openSignInOnStart) {
        if (state.openSignInOnStart) {
            rootViewModel.onSignInOnStartHandled()
            navController.navigate(SignInDestination)
        }
    }
    // A scanova://pair link: only while signed out (a server change would move the
    // account's documents to another server); otherwise it is dropped.
    LaunchedEffect(state.pendingPairingCode, state.signedIn) {
        val code = state.pendingPairingCode ?: return@LaunchedEffect
        rootViewModel.onPairingLinkConsumed()
        if (!state.signedIn) navController.navigate(ServerDestination(code))
    }

    val startTool: (AppTool) -> Unit = { tool ->
        when (tool) {
            AppTool.ID_CARD -> navController.navigateToScan(idCard = true)
            else -> navController.navigateToTool(tool)
        }
    }

    MainShell(navController = navController, onScan = { navController.navigateToScan() }) {
        documentsScreens(
            navController = navController,
            onScan = { navController.navigateToScan() },
            onTool = startTool,
            onShowAllTools = { navController.selectTab(MainTab.TOOLS) },
            onEditDocument = { documentId, pageIndex -> navController.navigateToEditor(documentId, pageIndex) },
            onShareDocuments = { documentIds -> navController.navigateToShare(documentIds) },
            onExtractText = { documentId -> navController.navigateToExtractText(documentId) },
            onOpenSettings = { navController.navigateToSettings() },
            onSignIn = { navController.navigate(SignInDestination) },
            onCreateAccount = { navController.navigate(SignUpDestination) },
        )
        scanGraph(
            navController = navController,
            onDocumentSaved = { documentId ->
                // Back where the scan started, with the new document opened on top.
                leaveScan()
                navController.navigateToDocument(documentId)
            },
            onExit = leaveScan,
        )
        editorScreens(navController = navController, onDone = { navController.popBackStack() })
        toolsScreens(
            navController = navController,
            onScan = { idCard -> navController.navigateToScan(idCard) },
            onOpenDocument = { documentId -> navController.navigateToDocument(documentId) },
            onEditDocument = { documentId -> navController.navigateToEditor(documentId) },
        )
        settingsScreens(
            navController = navController,
            onChangePassword = { navController.navigate(ChangePasswordDestination) },
            onSignIn = { navController.navigate(SignInDestination) },
            onCreateAccount = { navController.navigate(SignUpDestination) },
            onChooseServer = { navController.navigate(ServerDestination()) },
        )
        composable<ChangePasswordDestination> {
            ChangePasswordRoute(forced = false, onDone = { navController.popBackStack() })
        }
        composable<SignInDestination> {
            SignInRoute(
                onBack = { navController.popBackStack() },
                onCreateAccount = { navController.replace(SignUpDestination) },
                onSignedIn = { navController.popBackStack() },
            )
        }
        composable<SignUpDestination> {
            SignUpRoute(
                onBack = { navController.popBackStack() },
                onSignIn = { navController.replace(SignInDestination) },
                onSignedUp = { navController.popBackStack() },
            )
        }
        composable<ServerDestination> { entry ->
            PairingRoute(
                initialPairingCode = entry.toRoute<ServerDestination>().pairingCode,
                onInitialPairingCodeConsumed = {},
                onBack = { navController.popBackStack() },
                onPaired = { navController.popBackStack() },
            )
        }
    }
}

/** Swaps the current screen for [route] (sign-in ⇄ sign-up), so Back returns to where both started. */
private fun NavController.replace(route: Any) {
    popBackStack()
    navigate(route)
}
