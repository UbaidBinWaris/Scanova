package app.scanova.feature.tools.navigation

import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.toRoute
import app.scanova.core.data.export.ExportFormat
import app.scanova.core.data.model.AppTool
import app.scanova.feature.tools.R
import app.scanova.feature.tools.common.SheetDialogProperties
import app.scanova.feature.tools.home.ToolsRoute
import app.scanova.feature.tools.importer.ImportRoute
import app.scanova.feature.tools.merge.MergeRoute
import app.scanova.feature.tools.pick.PickDocumentRoute
import app.scanova.feature.tools.share.ShareSheetRoute
import app.scanova.feature.tools.share.SheetPurpose
import app.scanova.feature.tools.split.SplitRoute
import app.scanova.feature.tools.text.ExtractTextRoute
import kotlinx.serialization.Serializable

/** The Tools tab: every tool, grouped. */
@Serializable
data object ToolsDestination

/** One tool's flow (pick photos or files, choose documents to merge, …). */
@Serializable
data class ToolFlowDestination(val tool: AppTool)

/** Share one or more documents: as PDF, as separate images, or as one long image. */
@Serializable
data class ShareDestination(val documentIds: List<String>)

/** Save one or more documents to the device: as PDF, JPEG per page, one long JPEG, or text. */
@Serializable
data class SaveDestination(val documentIds: List<String>)

/** A document's text, to read, copy, share or save as a text file. */
@Serializable
data class ExtractTextDestination(val documentId: String)

/** Tools tab → "Share as images": pick a document first. */
@Serializable
internal data object ShareAsImagesDestination

/**
 * The share sheet opened on a chosen format ([format]: an [ExportFormat] name;
 * kept a plain string so release builds need no keep rule for the enum).
 */
@Serializable
internal data class ShareWithFormatDestination(val documentIds: List<String>, val format: String)

fun NavController.navigateToTool(tool: AppTool) = navigate(ToolFlowDestination(tool))

fun NavController.navigateToShare(documentIds: List<String>) = navigate(ShareDestination(documentIds))

/** "Save to device": choose PDF, JPEG, long JPEG or text, then where on the device. */
fun NavController.navigateToSave(documentIds: List<String>) = navigate(SaveDestination(documentIds))

fun NavController.navigateToExtractText(documentId: String) = navigate(ExtractTextDestination(documentId))

/**
 * The tools feature.
 *
 * @param onScan opens the camera ([idCard]: ID card mode).
 * @param onOpenDocument shows a document (e.g. the one a tool just made).
 * @param onEditDocument opens the page editor for a document. No tool needs it at the moment
 *   (pictures go through the scanner's review instead); kept for the app's wiring.
 * @param onImportPhotos starts the scanner's photo import: picked pictures ([pictureUris], or
 *   the photo picker when empty) are trimmed, straightened and enhanced like camera photos.
 */
@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.toolsScreens(
    navController: NavController,
    onScan: (idCard: Boolean) -> Unit,
    onOpenDocument: (documentId: String) -> Unit,
    onEditDocument: (documentId: String) -> Unit,
    onImportPhotos: (pictureUris: List<String>) -> Unit = {},
) {
    composable<ToolsDestination> {
        ToolsRoute(
            onScan = onScan,
            onTool = { tool ->
                when (tool) {
                    // Photos are scanned, not filed: the scanner finds and trims each page.
                    AppTool.IMPORT_IMAGES -> onImportPhotos(emptyList())
                    else -> navController.navigateToTool(tool)
                }
            },
            onShareAsImages = { navController.navigate(ShareAsImagesDestination) },
        )
    }

    composable<ToolFlowDestination> { entry ->
        // A finished flow leaves the back stack, so "back" from its result returns to where the tool was started.
        val close = { navController.popIfCurrent(entry) }
        val thenOpen = { id: String ->
            close()
            onOpenDocument(id)
        }
        when (entry.toRoute<ToolFlowDestination>().tool) {
            AppTool.IMPORT_FILES -> ImportRoute(
                onClose = close,
                onOpenDocument = thenOpen,
                onScanPictures = { uris ->
                    close()
                    onImportPhotos(uris)
                },
            )
            // Normally sent straight to the photo import by the app; handled here too so no route is a dead end.
            AppTool.IMPORT_IMAGES -> LaunchedEffect(Unit) {
                close()
                onImportPhotos(emptyList())
            }
            AppTool.MERGE_PDF -> MergeRoute(onClose = close, onScan = { onScan(false) }, onMerged = thenOpen)
            AppTool.SPLIT_PDF -> SplitRoute(onClose = close, onScan = { onScan(false) }, onOpenDocument = thenOpen)
            AppTool.EXTRACT_TEXT -> PickDocumentRoute(
                title = R.string.feature_tools_extract_text,
                hint = R.string.feature_tools_extract_text_pick_hint,
                onBack = close,
                onScan = { onScan(false) },
                onPicked = { id ->
                    navController.navigate(ExtractTextDestination(id)) {
                        popUpTo<ToolFlowDestination> { inclusive = true }
                    }
                },
            )
            // Normally sent straight to the camera by the app; handled here too so no route is a dead end.
            AppTool.ID_CARD -> LaunchedEffect(Unit) {
                close()
                onScan(true)
            }
        }
    }

    composable<ShareAsImagesDestination> { entry ->
        PickDocumentRoute(
            title = R.string.feature_tools_share_as_images,
            hint = R.string.feature_tools_share_as_images_pick_hint,
            onBack = { navController.popIfCurrent(entry) },
            onScan = { onScan(false) },
            onPicked = { id ->
                navController.navigate(ShareWithFormatDestination(listOf(id), ExportFormat.IMAGES.name)) {
                    popUpTo<ShareAsImagesDestination> { inclusive = true }
                }
            },
        )
    }

    dialog<ShareDestination>(dialogProperties = SheetDialogProperties) { entry ->
        ShareSheetRoute(
            documentIds = entry.toRoute<ShareDestination>().documentIds,
            initialFormat = null,
            onClose = { navController.popIfCurrent(entry) },
        )
    }

    dialog<SaveDestination>(dialogProperties = SheetDialogProperties) { entry ->
        ShareSheetRoute(
            documentIds = entry.toRoute<SaveDestination>().documentIds,
            initialFormat = null,
            purpose = SheetPurpose.SAVE,
            onClose = { navController.popIfCurrent(entry) },
        )
    }

    dialog<ShareWithFormatDestination>(dialogProperties = SheetDialogProperties) { entry ->
        val route = entry.toRoute<ShareWithFormatDestination>()
        ShareSheetRoute(
            documentIds = route.documentIds,
            initialFormat = ExportFormat.entries.firstOrNull { it.name == route.format },
            onClose = { navController.popIfCurrent(entry) },
        )
    }

    composable<ExtractTextDestination> { entry ->
        ExtractTextRoute(onBack = { navController.popIfCurrent(entry) })
    }
}

/** Pops [entry] only while it is on top, so a late or repeated callback never pops the screen below it. */
private fun NavController.popIfCurrent(entry: NavBackStackEntry) {
    if (currentBackStackEntry?.id == entry.id) popBackStack()
}
