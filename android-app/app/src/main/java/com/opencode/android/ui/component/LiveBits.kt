package com.opencode.android.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opencode.android.ui.theme.LocalOcColors

/* =============================================================================
 * 闪烁光标 —— steps(1) 硬切,周期 1.05s。默认用活动绿;品牌处可传 accent。
 * 放在文字行尾即可。
 * ========================================================================== */
@Composable
fun BlinkingCursor(
    color: Color = LocalOcColors.current.signal,
    width: Dp = 9.dp,
    height: Dp = 19.dp,
) {
    val t = rememberInfiniteTransition(label = "cursor")
    val alpha by t.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1050
                1f at 0 using LinearEasing
                1f at 524 using LinearEasing
                0f at 525 using LinearEasing
                0f at 1050 using LinearEasing
            },
        ),
        label = "blink",
    )
    Box(
        Modifier
            .size(width, height)
            .graphicsLayer { this.alpha = alpha }
            .clip(RoundedCornerShape(1.dp))
            .background(color),
    )
}

/* =============================================================================
 * 在线呼吸点 —— 7dp 实心点 + 向外扩散淡出的光晕(2.4s)。离线传 active=false。
 * 「只给活的东西用」:在线状态、运行中、活跃会话。
 * ========================================================================== */
@Composable
fun OnlineDot(active: Boolean = true, modifier: Modifier = Modifier) {
    val c = LocalOcColors.current
    if (!active) {
        Box(modifier.size(7.dp).clip(CircleShape).background(c.ink4))
        return
    }
    val t = rememberInfiniteTransition(label = "dot")
    val scale by t.animateFloat(
        initialValue = 0.7f, targetValue = 1.5f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "scale",
    )
    val ringAlpha by t.animateFloat(
        initialValue = 0.35f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "alpha",
    )
    Box(modifier.size(13.dp), contentAlignment = Alignment.Center) {
        // 光晕
        Box(
            Modifier
                .size(13.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale; alpha = ringAlpha }
                .clip(CircleShape)
                .background(c.accent),
        )
        // 实心点
        Box(Modifier.size(7.dp).clip(CircleShape).background(c.accent))
    }
}
