package com.opencode.android.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opencode.android.ui.theme.LocalOcColors
import com.opencode.android.ui.theme.OcType

/* =============================================================================
 * 1) 按压反馈 —— 所有可点元素都挂这个,scale(0.975) + 140ms
 * ========================================================================== */
@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, tween(140), label = "press")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(
            interactionSource = interaction,
            indication = null,         // Plain 不用涟漪,只用缩放
            enabled = enabled,
            onClick = onClick,
        )
}

/* =============================================================================
 * 2) 发丝线 —— Plain 的唯一分隔手段,恒 1px
 * ========================================================================== */
@Composable
fun Hairline(modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    val c = LocalOcColors.current
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .drawBehind {
                val x0 = with(this) { inset.toPx() }
                drawLine(
                    color = c.line,
                    start = Offset(x0, size.height / 2),
                    end = Offset(size.width - x0, size.height / 2),
                    strokeWidth = size.height,
                )
            }
    )
}

/* =============================================================================
 * 3) 大写小标签 —— 等宽 + 字距 0.12em,可选绿色 `>` 前缀(终端味)
 * ========================================================================== */
@Composable
fun MonoLabel(text: String, modifier: Modifier = Modifier, prefix: String? = null) {
    val c = LocalOcColors.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (prefix != null) {
            Text(prefix, style = OcType.monoLabel, color = c.accent)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text.uppercase(),
            style = OcType.monoLabel,
            color = c.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
