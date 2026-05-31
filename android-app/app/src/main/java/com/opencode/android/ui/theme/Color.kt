package com.opencode.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/* =============================================================================
 * OpenCode Mobile — Plain 设计 · 颜色系统
 *
 * 用法:把界面包在 OcTheme { ... } 里,然后在任意 Composable 中:
 *     val c = LocalOcColors.current
 *     Text("opencode", color = c.ink)
 *
 * 不要直接用 MaterialTheme.colorScheme —— Plain 的语义色都在 OcColors 里。
 * ========================================================================== */

@Immutable
data class OcColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val raised: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val ink4: Color,
    val line: Color,
    val line2: Color,
    val codeBg: Color,
    val accent: Color,      // 强调绿(动作按钮 / 聚焦线 / Agent 名)
    val accentInk: Color,   // 强调色上的文字
    val signal: Color,      // 活动绿(光标 / 在线呼吸点 / 运行中)—— 只给「活的」东西
    val userBg: Color,
    val userInk: Color,
    val isDark: Boolean,
)

val OcLight = OcColors(
    bg = Color(0xFFF4F2EC),
    surface = Color(0xFFFAF9F5),
    surface2 = Color(0xFFEFECE4),
    raised = Color(0xFFFFFFFF),
    ink = Color(0xFF1B1A17),
    ink2 = Color(0xFF57544C),
    ink3 = Color(0xFF8F8B80),
    ink4 = Color(0xFFB6B1A4),
    line = Color(0xFFE4E0D6),
    line2 = Color(0xFFD6D1C4),
    codeBg = Color(0xFFEDEAE1),
    accent = Color(0xFF4E8A60),
    accentInk = Color(0xFFFFFFFF),
    signal = Color(0xFF5E9C6E),
    userBg = Color(0xFF1B1A17),
    userInk = Color(0xFFF6F4EE),
    isDark = false,
)

val OcDark = OcColors(
    bg = Color(0xFF161512),
    surface = Color(0xFF1C1B17),
    surface2 = Color(0xFF211F1A),
    raised = Color(0xFF23211C),
    ink = Color(0xFFECE8DF),
    ink2 = Color(0xFFA8A298),
    ink3 = Color(0xFF756F64),
    ink4 = Color(0xFF564F45),
    line = Color(0xFF2E2B25),
    line2 = Color(0xFF3A362E),
    codeBg = Color(0xFF211F1A),
    accent = Color(0xFF4E8A60),
    accentInk = Color(0xFFFFFFFF),
    signal = Color(0xFF5E9C6E),
    userBg = Color(0xFFECE8DF),
    userInk = Color(0xFF1B1A17),
    isDark = true,
)

/* 可选强调色(设置页让用户切换) */
enum class OcAccent(val color: Color, val label: String) {
    Green(Color(0xFF4E8A60), "Green"),     // Plain 默认
    Ochre(Color(0xFFB8623A), "Ochre"),
    Indigo(Color(0xFF5B67C4), "Indigo"),
    Taupe(Color(0xFF8A8276), "Taupe"),
}

val LocalOcColors = staticCompositionLocalOf { OcLight }

/* M3 仅用于兜底(涟漪、系统组件);Plain 的视觉一律走 OcColors。 */
private fun OcColors.toMaterial() = if (isDark)
    darkColorScheme(background = bg, surface = surface, primary = accent, onPrimary = accentInk, onBackground = ink, onSurface = ink)
else
    lightColorScheme(background = bg, surface = surface, primary = accent, onPrimary = accentInk, onBackground = ink, onSurface = ink)

@Composable
fun OcTheme(
    darkTheme: Boolean,
    accent: Color = OcAccent.Green.color,
    content: @Composable () -> Unit,
) {
    val base = if (darkTheme) OcDark else OcLight
    val colors = remember(darkTheme, accent) { base.copy(accent = accent) }
    CompositionLocalProvider(LocalOcColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toMaterial(),
            typography = OcTypography,
            content = content,
        )
    }
}
