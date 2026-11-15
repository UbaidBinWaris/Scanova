package app.scanova.feature.documents.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.scanova.core.data.model.AppTool
import app.scanova.feature.documents.files.FilesRoute
import app.scanova.feature.documents.home.HomeRoute
import app.scanova.feature.documents.viewer.ViewerRoute
import kotlinx.serialization.Serializable

/** The Home tab: search, quick actions, recent documents. Where the app starts. */
@Serializable
data object HomeDestination

/** The Files tab: every document, sortable, selectable. */
@Serializable
data object FilesDestination

@Serializable
data class ViewerDestination(val documentId: String)

fun NavController.navigateToDocument(documentId: String) = navigate(ViewerDestination(documentId))

/** Switches to the Files tab the way the bottom bar does, so each tab keeps its own back stack. */
private fun NavController.showFilesTab() = navigate(FilesDestination) {
    popUpTo(HomeDestination) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/**
 * The documents feature: Home, Files and the document viewer. Everything that
 * belongs to another feature is reached through a callback; the app module
 * decides where each one goes.
 *
 * @param onScan opens the camera.
 * @param onTool starts a tool (import, ID card, merge, …).
 * @param onShowAllTools switches to the Tools tab.
 * @param onEditDocument opens the page editor (crop, filters, markup) at a page.
 * @param onShareDocuments opens the share sheet (PDF, images, long image) for one or more documents.
 * @param onExtractText shows a document's text to copy or share.
 * @param onSignIn opens sign-in (Home's "Back up" chip).
 * @param onOpenSettings, onCreateAccount are kept for the app's wiring; the Me tab now
 *   offers both, so these screens no longer need them.
 */
@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.documentsScreens(
    navController: NavController,
    onScan: () -> Unit,
    onTool: (AppTool) -> Unit,
    onShowAllTools: () -> Unit,
    onEditDocument: (documentId: String, pageIndex: Int) -> Unit,
    onShareDocuments: (documentIds: List<String>) -> Unit,
    onExtractText: (documentId: String) -> Unit,
    onOpenSettings: () -> Unit,
    onSignIn: () -> Unit,
    onCreateAccount: () -> Unit,
) {
    composable<HomeDestination> {
        HomeRoute(
            onScan = onScan,
            onTool = onTool,
            onShowAllTools = onShowAllTools,
            onOpenDocument = navController::navigateToDocument,
            onSeeAll = navController::showFilesTab,
            onSignIn = onSignIn,
        )
    }
    composable<FilesDestination> {
        FilesRoute(
            onScan = onScan,
            onImport = { onTool(AppTool.IMPORT_IMAGES) },
            onOpenDocument = navController::navigateToDocument,
            onShareDocuments = { ids -> if (ids.isNotEmpty()) onShareDocuments(ids) },
        )
    }
    composable<ViewerDestination> {
        ViewerRoute(
            onBack = { navController.popBackStack() },
            onEditDocument = onEditDocument,
            onShareDocument = { documentId -> onShareDocuments(listOf(documentId)) },
            onExtractText = onExtractText,
        )
    }
}
