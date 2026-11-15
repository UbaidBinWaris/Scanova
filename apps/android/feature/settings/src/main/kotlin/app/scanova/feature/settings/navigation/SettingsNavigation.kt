package app.scanova.feature.settings.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.scanova.feature.settings.SettingsRoute
import app.scanova.feature.settings.sessions.SessionsRoute
import kotlinx.serialization.Serializable

@Serializable
data object SettingsDestination

@Serializable
internal data object SessionsDestination

fun NavController.navigateToSettings() = navigate(SettingsDestination)

/** @param onChangePassword opens the password-change screen, which belongs to another feature. */
fun NavGraphBuilder.settingsScreens(navController: NavController, onChangePassword: () -> Unit) {
    composable<SettingsDestination> {
        SettingsRoute(
            onBack = { navController.popBackStack() },
            onChangePassword = onChangePassword,
            onShowSessions = { navController.navigate(SessionsDestination) },
        )
    }
    composable<SessionsDestination> {
        SessionsRoute(onBack = { navController.popBackStack() })
    }
}
