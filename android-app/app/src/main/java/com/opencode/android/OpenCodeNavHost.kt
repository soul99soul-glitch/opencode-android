package com.opencode.android

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.net.Uri
import com.opencode.android.data.repository.PreferencesRepository
import com.opencode.android.ui.screen.ChatScreen
import com.opencode.android.ui.screen.SessionsScreen
import com.opencode.android.ui.screen.SettingsScreen
import com.opencode.android.ui.screen.SetupScreen

/**
 * iOS 风格页面过渡
 *
 * 前进: 新页面从右侧滑入(全屏宽度) + 旧页面同步左移, 350ms ease-out
 * 返回: 当前页面右滑退出, 下层同步回归, 300ms ease-out
 */

@Composable
fun OpenCodeNavHost() {
    val context = LocalContext.current
    val prefs = remember { PreferencesRepository(context) }
    val isSetupDone by prefs.isSetupDone.collectAsState(initial = null)

    val navController = rememberNavController()

    LaunchedEffect(Unit) {
        prefs.migrateLegacySecrets()
    }

    if (isSetupDone == null) return

    val startDestination = if (isSetupDone == true) "sessions" else "setup"

    NavHost(
        navController = navController,
        startDestination = startDestination,
        // ── 前进: 新页面从右侧滑入覆盖, 旧页面不动 ──
        enterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(durationMillis = 350),
                initialOffset = { it / 3 },
            ) + fadeIn(tween(durationMillis = 150, delayMillis = 80))
        },
        // ── 前进时旧页面: 原地不动, 仅微微淡出 ──
        exitTransition = {
            fadeOut(tween(durationMillis = 100))
        },
        // ── 返回: 下层从左侧微微回归 (在 Chat 右滑时从底下浮现) ──
        popEnterTransition = {
            fadeIn(tween(durationMillis = 250))
        },
        // ── 返回时当前页面: 向右滑出 ──
        popExitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(durationMillis = 300),
                targetOffset = { it / 3 },
            ) + fadeOut(tween(durationMillis = 150))
        },
    ) {
        composable("setup") {
            SetupScreen {
                navController.navigate("sessions") { popUpTo("setup") { inclusive = true } }
            }
        }
        composable("sessions") {
            SessionsScreen(
                onSessionClick = { id, title -> navController.navigate("chat/$id?title=${Uri.encode(title ?: "")}") },
                onSettingsClick = { navController.navigate("settings") }
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() }) {
                navController.navigate("setup") {
                    popUpTo(0) { inclusive = true }
                }
            }
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
            ChatScreen(
                sessionId = sessionId,
                sessionTitle = title,
                onBack = { navController.popBackStack() },
                onSubagentNavigate = { subId -> navController.navigate("chat/$subId") },
            )
        }
    }
}
