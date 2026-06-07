package com.opencode.android.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.android.ui.theme.LocalOcColors
import com.opencode.android.ui.theme.OcType
import com.opencode.android.ui.theme.SuperellipseShape
import androidx.compose.ui.res.stringResource
import com.opencode.android.R

/* =============================================================================
 * Thinking 折叠块 —— 默认收起;点三角旋转 90°,内容淡入上浮,左侧带竖线。
 * ========================================================================== */
@Composable
fun ThinkingBlock(text: String, chars: Int, modifier: Modifier = Modifier) {
    val c = LocalOcColors.current
    var open by remember { mutableStateOf(false) }
    val rot by animateFloatAsState(if (open) 90f else 0f, tween(220), label = "tri")

    Column(modifier) {
        Row(
            Modifier.pressable { open = !open }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Canvas(Modifier.size(13.dp).rotate(rot)) {
                val p = Path().apply {
                    moveTo(size.width * 0.30f, size.height * 0.18f)
                    lineTo(size.width * 0.78f, size.height * 0.50f)
                    lineTo(size.width * 0.30f, size.height * 0.82f)
                    close()
                }
                drawPath(p, c.ink4)
            }
            Text(stringResource(R.string.chat_thinking_collapsed), style = OcType.monoStrong, color = c.ink3)
            Text(stringResource(R.string.chat_chars_count, chars), style = OcType.mono, color = c.ink4)
        }
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 6 },
            exit = fadeOut(tween(120)),
        ) {
            Text(
                text,
                style = OcType.mono.copy(lineHeight = 21.sp),
                color = c.ink3,
                modifier = Modifier
                    .padding(start = 6.dp, top = 9.dp)
                    .drawBehind {
                        drawLine(
                            c.line2,
                            Offset(0f, 0f),
                            Offset(0f, size.height),
                            strokeWidth = 2.dp.toPx(),
                        )
                    }
                    .padding(start = 13.dp),
            )
        }
    }
}

/* =============================================================================
 * 工具调用行 —— 可点击展开/折叠
 * 折叠: 状态色方点 · 工具名 · 参数 · ✓/▼
 * 展开: 显示 input 参数 + output 内容
 * status: "done" | "run" | "queued"
 * ========================================================================== */
@Composable
fun ToolCallRow(
    tool: String,
    arg: String,
    status: String,
    output: String? = null,
    input: String? = null,
    modifier: Modifier = Modifier,
) {
    val c = LocalOcColors.current
    var expanded by remember { mutableStateOf(false) }
    val outputText = output.orEmpty()
    val inputText = input.orEmpty()
    val outputLines = outputText.lines().size
    val hasDetail = outputText.isNotBlank() || inputText.isNotBlank()
    val isLongOutput = outputLines > 6 || outputText.length > 400
    val dotColor = when (status) {
        "done" -> c.accent
        "run" -> c.accent
        else -> c.ink4
    }
    val detailHint = when {
        isLongOutput -> "$outputLines lines"
        outputText.isNotBlank() -> "${outputText.length} chars"
        inputText.isNotBlank() -> "input"
        else -> null
    }

    Column(
        modifier
            .padding(vertical = 1.dp)
    ) {
        // Header row — always visible, clickable to expand/collapse
        Row(
            Modifier
                .then(if (hasDetail) Modifier.pressable { expanded = !expanded } else Modifier)
                .background(c.codeBg, SuperellipseShape(8.dp, 4f))
                .padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Canvas(Modifier.size(7.dp)) {
                drawRoundRect(dotColor, cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
            }
            Text(tool, style = OcType.monoStrong, color = c.ink)
            Text(
                arg,
                style = OcType.mono,
                color = c.ink3,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!expanded && detailHint != null) {
                Text(detailHint, style = OcType.mono.copy(fontSize = 10.sp), color = c.ink4)
            }
            if (status == "done" && !hasDetail) {
                // Static checkmark
                Canvas(Modifier.size(13.dp)) {
                    val p = Path().apply {
                        moveTo(size.width * 0.2f, size.height * 0.52f)
                        lineTo(size.width * 0.42f, size.height * 0.74f)
                        lineTo(size.width * 0.82f, size.height * 0.28f)
                    }
                    drawPath(p, c.accent, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.7.dp.toPx()))
                }
            }
            if (hasDetail) {
                // Expand/collapse chevron
                val rot by animateFloatAsState(
                    if (expanded) 180f else 0f,
                    tween(200), label = "chev"
                )
                Canvas(Modifier.size(13.dp).rotate(rot)) {
                    val p = Path().apply {
                        moveTo(size.width * 0.2f, size.height * 0.38f)
                        lineTo(size.width * 0.5f, size.height * 0.68f)
                        lineTo(size.width * 0.8f, size.height * 0.38f)
                    }
                    drawPath(p, c.ink4, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
                }
            }
            if (status == "run") OnlineDot()
        }

        // Expanded detail — default collapsed; scroll long bash/log output
        if (hasDetail) {
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(120)),
            ) {
                val scroll = rememberScrollState()
                Column(
                    Modifier
                        .background(c.codeBg.copy(alpha = 0.5f), SuperellipseShape(8.dp, 4f))
                        .padding(horizontal = 13.dp, vertical = 9.dp)
                        .heightIn(max = 220.dp)
                        .verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (inputText.isNotBlank()) {
                        SelectionContainer {
                            Text(inputText.take(4000), style = OcType.mono.copy(lineHeight = 18.sp), color = c.ink2)
                        }
                    }
                    if (inputText.isNotBlank() && outputText.isNotBlank()) {
                        Hairline()
                    }
                    if (outputText.isNotBlank()) {
                        SelectionContainer {
                            Text(
                                outputText.take(12_000),
                                style = OcType.mono.copy(lineHeight = 18.sp, fontSize = 11.sp),
                                color = c.ink3,
                            )
                        }
                    }
                }
            }
        }
    }
}
