package app.scanova.feature.documents.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.scanova.feature.documents.library.LibraryRoute
import app.scanova.feature.documents.viewer.ViewerRoute
import kotlinx.serialization.Serializable

/** The library: the start of the signed-in area. */
@Serializable
data object LibraryDestination

@Serializable
data class ViewerDestination(val documentId: String)

fun NavController.navigateToDocument(documentId: String) = navigate(ViewerDestination(documentId))

fun NavGraphBuilder.documentsScreens(
    navController: NavController,
    onScan: () -> Unit,
    onOpenSettings: () -> Unit,
    onSignIn: () -> Unit,
    onCreateAccount: () -> Unit,
) {
    composable<LibraryDestination> {
        LibraryRoute(
            onScan = onScan,
            onOpenDocument = navController::navigateToDocument,
            onOpenSettings = onOpenSettings,
            onSignIn = onSignIn,
            onCreateAccount = onCreateAccount,
        )
    }
    composable<ViewerDestination> {
        ViewerRoute(onBack = { navController.popBackStack() })
    }
}
