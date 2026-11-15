package app.scanova.feature.scan.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import app.scanova.feature.scan.camera.CameraRoute
import app.scanova.feature.scan.crop.CropRoute
import app.scanova.feature.scan.review.ReviewRoute
import kotlinx.serialization.Serializable

/** The scan flow: camera → adjust page → review → saved document. */
@Serializable
data object ScanGraph

@Serializable
internal data object CameraDestination

@Serializable
internal data class CropDestination(val pageId: String)

@Serializable
internal data object ReviewDestination

fun NavController.navigateToScan() = navigate(ScanGraph)

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
        composable<CameraDestination> {
            CameraRoute(
                onPageCaptured = { pageId -> navController.navigate(CropDestination(pageId)) },
                onReview = showReview,
                onClose = onExit,
            )
        }
        composable<CropDestination> {
            CropRoute(
                onDone = showReview,
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
