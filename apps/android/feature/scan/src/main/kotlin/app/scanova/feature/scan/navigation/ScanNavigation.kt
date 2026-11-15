package app.scanova.feature.scan.navigation

import androidx.compose.runtime.remember
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import androidx.navigation.toRoute
import app.scanova.feature.scan.camera.CameraRoute
import app.scanova.feature.scan.crop.CropRoute
import app.scanova.feature.scan.editor.EditorRoute
import app.scanova.feature.scan.review.ReviewRoute
import kotlinx.serialization.Serializable

/**
 * The scan flow: camera (pages one after another) → review (look, crop, order) → saved document.
 *
 * @property idCard scan an ID card: front and back are captured and put on one page.
 */
@Serializable
data class ScanGraph(val idCard: Boolean = false)

@Serializable
internal data object CameraDestination

@Serializable
internal data class CropDestination(val pageId: String)

@Serializable
internal data object ReviewDestination

/** Edit a saved document's pages (crop, rotate, filters, markup, order), starting at [pageIndex]. */
@Serializable
data class EditorDestination(val documentId: String, val pageIndex: Int = 0)

fun NavController.navigateToScan(idCard: Boolean = false) = navigate(ScanGraph(idCard))

fun NavController.navigateToEditor(documentId: String, pageIndex: Int = 0) =
    navigate(EditorDestination(documentId, pageIndex))

/**
 * @param onDocumentSaved called with the new document's id when the scan was saved.
 * @param onExit called when the user leaves the flow without saving.
 */
fun NavGraphBuilder.scanGraph(
    navController: NavController,
    onDocumentSaved: (documentId: String) -> Unit,
    onExit: () -> Unit,
) {
    // Review is the hub of the flow: go back to it when it is already on the
    // stack, otherwise replace the camera with it.
    val showReview = {
        if (!navController.popBackStack<ReviewDestination>(inclusive = false)) {
            navController.navigate(ReviewDestination) {
                popUpTo<CameraDestination> { inclusive = true }
            }
        }
    }

    navigation<ScanGraph>(startDestination = CameraDestination) {
        composable<CameraDestination> { entry ->
            // The mode the scan was started in is an argument of the whole flow.
            val idCard = remember(entry) {
                runCatching { navController.getBackStackEntry<ScanGraph>().toRoute<ScanGraph>().idCard }.getOrDefault(false)
            }
            CameraRoute(
                idCard = idCard,
                onReview = showReview,
                onClose = onExit,
            )
        }
        composable<CropDestination> {
            CropRoute(
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
        composable<ReviewDestination> {
            ReviewRoute(
                onAddPage = { navController.navigate(CameraDestination) },
                onAdjustPage = { pageId -> navController.navigate(CropDestination(pageId)) },
                onSaved = onDocumentSaved,
                onDiscarded = onExit,
            )
        }
    }
}

/**
 * The page editor for saved documents.
 *
 * @param onDone called when the editor closes (saved or not).
 */
@Suppress("UNUSED_PARAMETER") // Kept for symmetry with scanGraph; the editor is a single screen.
fun NavGraphBuilder.editorScreens(navController: NavController, onDone: () -> Unit) {
    composable<EditorDestination> {
        EditorRoute(onDone = onDone)
    }
}
