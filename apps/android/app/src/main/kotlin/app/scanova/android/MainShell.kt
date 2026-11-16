package app.scanova.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import app.scanova.core.designsystem.component.BottomBarItem
import app.scanova.core.designsystem.component.ScanovaBottomBar
import app.scanova.core.designsystem.theme.Motion
import app.scanova.feature.documents.navigation.FilesDestination
import app.scanova.feature.documents.navigation.HomeDestination
import app.scanova.feature.settings.navigation.MeDestination
import app.scanova.feature.tools.navigation.ToolsDestination
import kotlin.reflect.KClass

/**
 * The four tabs of the bottom bar, in order (two each side of the Scan button).
 * Unselected tabs show the outlined icon, the selected one its solid (rounded)
 * twin, the style used everywhere else in the app.
 */
internal enum class MainTab(
    val route: Any,
    val routeClass: KClass<*>,
    val label: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    HOME(HomeDestination, HomeDestination::class, R.string.tab_home, Icons.Outlined.Home, Icons.Rounded.Home),
    FILES(FilesDestination, FilesDestination::class, R.string.tab_files, Icons.Outlined.Folder, Icons.Rounded.Folder),
    TOOLS(ToolsDestination, ToolsDestination::class, R.string.tab_tools, Icons.Outlined.GridView, Icons.Rounded.GridView),
    ME(MeDestination, MeDestination::class, R.string.tab_me, Icons.Outlined.Person, Icons.Rounded.Person),
}

private fun NavDestination?.tab(): MainTab? =
    this?.let { destination -> MainTab.entries.firstOrNull { destination.hasRoute(it.routeClass) } }

/**
 * Switches tabs the way people expect: each tab keeps its own back stack and
 * scroll position, and tapping the current tab again does nothing.
 */
internal fun NavController.selectTab(tab: MainTab) = navigate(tab.route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/**
 * The signed-in-or-not main area: the bottom bar on the four tab screens, and
 * nothing around full-screen flows (camera, viewer, editor, …), which slide the
 * bar away. Screens draw their own top bars and handle their own insets; the
 * bar's height is given to them as padding (and consumed, so they don't add the
 * navigation bar inset a second time).
 */
@Composable
internal fun MainShell(
    navController: NavHostController,
    onScan: () -> Unit,
    graph: androidx.navigation.NavGraphBuilder.() -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentTab = backStackEntry?.destination.tab()
    val items = MainTab.entries.map {
        BottomBarItem(label = stringResource(it.label), icon = it.icon, selectedIcon = it.selectedIcon)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            AnimatedVisibility(
                visible = currentTab != null,
                enter = slideInVertically(Motion.enter()) { it } + fadeIn(Motion.enter()),
                exit = slideOutVertically(Motion.exit()) { it } + fadeOut(Motion.exit()),
            ) {
                ScanovaBottomBar(
                    items = items,
                    selectedIndex = currentTab?.ordinal ?: -1,
                    onSelect = { index -> navController.selectTab(MainTab.entries[index]) },
                    scanIcon = Icons.Rounded.DocumentScanner,
                    scanLabel = stringResource(R.string.scan_document),
                    onScan = onScan,
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = HomeDestination,
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
            enterTransition = { fadeIn(Motion.enter()) + scaleIn(Motion.enter(), initialScale = 0.98f) },
            exitTransition = { fadeOut(Motion.exit()) },
            popEnterTransition = { fadeIn(Motion.enter()) },
            popExitTransition = { fadeOut(Motion.exit()) + androidx.compose.animation.scaleOut(Motion.exit(), targetScale = 0.98f) },
            builder = graph,
        )
    }
}
