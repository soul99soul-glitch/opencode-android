package com.opencode.android.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/* =============================================================================
 * SuperellipseShape —— 真·平滑圆角(squircle)
 *
 * 与原型 CSS 的 `corner-shape: superellipse(4)` 等价:对每个圆角按超椭圆
 *   |x|^n + |y|^n = 1   采样,n=4 即 iOS 风格 squircle。
 * n=2 等于普通圆角;n 越大越方。
 *
 * 用法:Modifier.clip(OcButtonShape) / .background(color, OcButtonShape)
 * 若想零自定义,可换社区库:io.github.racra:smooth-corner-rect-android-compose
 * ========================================================================== */
class SuperellipseShape(
    private val cornerRadius: Dp,
    private val n: Float = 4f,
    private val steps: Int = 16,
) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { cornerRadius.toPx() }.coerceAtMost(min(size.width, size.height) / 2f)
        if (r <= 0f) return Outline.Rectangle(size.toRect())

        val w = size.width
        val h = size.height
        val exp = 2f / n
        val path = Path()

        fun spow(v: Float): Float = sign(v) * abs(v).pow(exp)

        // 在以 (cx,cy) 为角心、半径 r 的超椭圆角上,从 a0 扫到 a1(角度制)
        fun corner(cx: Float, cy: Float, a0: Float, a1: Float) {
            for (i in 0..steps) {
                val t = Math.toRadians((a0 + (a1 - a0) * i / steps).toDouble())
                val x = cx + r * spow(cos(t).toFloat())
                val y = cy + r * spow(sin(t).toFloat())
                if (path.isEmpty) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }

        path.moveTo(r, 0f)
        path.lineTo(w - r, 0f)
        corner(w - r, r, -90f, 0f)      // top-right
        path.lineTo(w, h - r)
        corner(w - r, h - r, 0f, 90f)   // bottom-right
        path.lineTo(r, h)
        corner(r, h - r, 90f, 180f)     // bottom-left
        path.lineTo(0f, r)
        corner(r, r, 180f, 270f)        // top-left
        path.close()
        return Outline.Generic(path)
    }
}

private fun Size.toRect() = androidx.compose.ui.geometry.Rect(Offset.Zero, this)

/* Plain 里用到的形状常量 */
val OcButtonShape = SuperellipseShape(cornerRadius = 17.dp, n = 4f)  // 按钮
val OcCardShape = SuperellipseShape(cornerRadius = 14.dp, n = 4f)    // 成组设置卡
val OcBubbleShape = SuperellipseShape(cornerRadius = 16.dp, n = 3f)  // 气泡(略圆)

// 用户消息气泡: 三圆角 + 右下小尖角(5dp)指向发送者
val OcUserBubbleShape = androidx.compose.foundation.shape.RoundedCornerShape(
    topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 5.dp
)
