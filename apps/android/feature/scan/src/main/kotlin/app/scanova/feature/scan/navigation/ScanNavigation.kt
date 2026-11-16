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
import app.scanova.feature.scan.importer.PhotoImportRoute
import app.scanova.feature.scan.review.ReviewRoute
import kotlinx.serialization.Serializable

/**
 * The scan flow: camera (pages one after another) → review (look, crop, order) → saved document.
 * Photos from the gallery enter the same flow through [navigateToPhotoImport].
 *
 * @property idCard scan an ID card: front and back are captured and put on one page.
 */
@Serializable
data class ScanGraph(val idCard: Boolean = false)

@Serializable
internal data object CameraDestination

/**
 * Photos into the scan: the photo picker, then the pages are prepared like
 * camera captures. [pictureUris]: photos another screen already picked
 * (content URIs as strings); empty: the picker opens.
 */
@Serializable
internal data class PhotoImportDestination(val pictureUris: List<String> = emptyList())

@Serializable
internal data class CropDestination(val pageId: String)

@Serializable
internal data object ReviewDestination

/** Edit a saved document's pages (crop, rotate, filters, markup, order), starting at [pageIndex]. */
@Serializable
data class EditorDestination(val documentId: String, val pageIndex: Int = 0)

fun NavController.navigateToScan(idCard: Boolean = false) = navigate(ScanGraph(idCard))

/**
 * Starts a scan from photos in the gallery: each picked photo is trimmed to
 * its page, straightened and enhanced like a camera capture, then reviewed.
 * [pictureUris] skips the picker for photos the user already chose elsewhere
 * (readable by this app, e.g. from the system's file picker).
 */
fun NavController.navigateToPhotoImport(pictureUris: List<String> = emptyList()) =
    navigate(PhotoImportDestination(pictureUris))

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
    navigation<ScanGraph>(startDestination = CameraDestination) {
        composable<CameraDestination> { entry ->
            // The mode the scan was started in is an argument of the whole flow.
            val idCard = remember(entry) {
                runCatching { navController.getBackStackEntry<ScanGraph>().toRoute<ScanGraph>().idCard }.getOrDefault(false)
            }
            CameraRoute(
                idCard = idCard,
                onReview = { navController.showReviewInsteadOf<CameraDestination>() },
                onClose = onExit,
            )
        }
        composable<PhotoImportDestination> {
            PhotoImportRoute(
                onReview = { navController.showReviewInsteadOf<PhotoImportDestination>() },
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
                onAddPhotos = { navController.navigate(PhotoImportDestination()) },
                onAdjustPage = { pageId -> navController.navigate(CropDestination(pageId)) },
                onSaved = onDocumentSaved,
                onDiscarded = onExit,
            )
        }
    }
}

/**
 * Review is the hub of the flow: go back to it when it is already on the
 * stack, otherwise put it in place of the screen [T] (camera or photo import).
 */
private inline fun <reified T : Any> NavController.showReviewInsteadOf() {
    if (!popBackStack<ReviewDestination>(inclusive = false)) {
        navigate(ReviewDestination) {
            popUpTo<T> { inclusive = true }
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
