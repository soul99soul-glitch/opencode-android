package com.opencode.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.opencode.android.R

/* =============================================================================
 * 字体
 *
 * 可变字体:单一 .ttf 文件支持 100–900 全字重。
 * 需要为每个用到的 FontWeight 显式声明 Font 条目,
 * 否则 Compose 会 fallback 到系统字体。
 *
 * 规则:UI 文案 = Hanken;一切「机器信息」(主机/端口/ID/时间戳/模型名/代码/数量)= Mono。
 * ========================================================================== */

val Hanken = FontFamily(
    Font(R.font.hanken_grotesk_variable, FontWeight.Normal),
)

val Mono = FontFamily(
    Font(R.font.jetbrains_mono_variable, FontWeight.Normal),
)

/* 等宽数字对齐:tabular-nums + slashed-zero */
private const val MONO_FEATURES = "tnum, zero"

/* Plain 的字号梯度 —— 直接用这些常量,别在页面里散写 sp */
object OcType {
    // UI 无衬线(统一 -0.01em 收紧)
    val brand = TextStyle(fontWeight = FontWeight.Bold, fontSize = 27.sp, letterSpacing = (-0.02).em)
    val titleL = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = (-0.015).em)
    val title = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = 16.5.sp, letterSpacing = (-0.015).em)
    val rowTitle = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Medium, fontSize = 16.5.sp, letterSpacing = (-0.012).em)
    val body = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Normal, fontSize = 15.5.sp, lineHeight = 25.sp, letterSpacing = (-0.005).em)
    val bodyStrong = body.copy(fontWeight = FontWeight.Bold)
    val secondary = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Normal, fontSize = 13.5.sp, letterSpacing = (-0.005).em)
    val caption = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Normal, fontSize = 13.sp)

    // 等宽
    val mono = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, fontFeatureSettings = MONO_FEATURES)
    val monoStrong = mono.copy(fontWeight = FontWeight.SemiBold)
    val monoInput = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 17.sp, fontFeatureSettings = MONO_FEATURES)
    val code = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 13.sp, fontFeatureSettings = MONO_FEATURES)

    // 大写小标签:等宽 + 字距 0.12em
    val monoLabel = TextStyle(
        fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
        letterSpacing = 0.12.em, fontFeatureSettings = MONO_FEATURES,
    )
}

/* 给 MaterialTheme 兜底用 */
val OcTypography = Typography(
    bodyLarge = OcType.body,
    titleMedium = OcType.title,
    labelSmall = OcType.monoLabel,
)
