package com.opencode.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.opencode.android.data.repository.PreferencesRepository
import com.opencode.android.ui.screen.ChatScreen
import com.opencode.android.ui.screen.SessionsScreen
import com.opencode.android.ui.screen.SetupScreen

@Composable
fun OpenCodeNavHost() {
    val context = LocalContext.current
    val prefs = remember { PreferencesRepository(context) }
    val isSetupDone by prefs.isSetupDone.collectAsState(initial = null)

    val navController = rememberNavController()

    // Show splash while loading
    if (isSetupDone == null) return

    val startDestination = if (isSetupDone == true) "sessions" else "setup"

    NavHost(navController = navController, startDestination = startDestination) {
        composable("setup") {
            SetupScreen {
                navController.navigate("sessions") { popUpTo("setup") { inclusive = true } }
            }
        }
        composable("sessions") {
            SessionsScreen(
                onSessionClick = { id, title -> navController.navigate("chat/$id?title=${title ?: ""}") },
                onSettingsClick = { navController.navigate("setup") }
            )
        }
        composable(
            route = "chat/{sessionId}?title={title}",
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            val title = backStackEntry.arguments?.getString("title")?.ifBlank { null }
            ChatScreen(sessionId = sessionId, sessionTitle = title, onBack = { navController.popBackStack() })
        }
    }
}
