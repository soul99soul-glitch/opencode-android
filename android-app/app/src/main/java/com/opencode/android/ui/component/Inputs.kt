package com.opencode.android.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.opencode.android.ui.theme.LocalOcColors
import com.opencode.android.ui.theme.OcButtonShape
import com.opencode.android.ui.theme.OcType

/* =============================================================================
 * 下划线输入框 —— Plain 的标志:无盒子,只底部 1px 线;聚焦变绿。
 * 机器值(主机/端口/目录)用等宽:mono = true。
 * ========================================================================== */
@Composable
fun UnderlineField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    leading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    mono: Boolean = true,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = LocalOcColors.current
    var focused by remember { mutableStateOf(false) }
    val lineColor by animateColorAsState(if (focused) c.accent else c.line, tween(160), label = "underline")

    Column(modifier) {
        MonoLabel(label)
        Spacer(Modifier.height(9.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    val y = size.height
                    drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { leading() }
            Spacer(Modifier.size(12.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focused = it.isFocused },
                textStyle = (if (mono) OcType.monoInput else OcType.body).copy(color = c.ink),
                singleLine = true,
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(placeholder, style = if (mono) OcType.monoInput else OcType.body, color = c.ink4)
                    }
                    inner()
                },
            )
            if (trailing != null) {
                Spacer(Modifier.size(8.dp))
                trailing()
            }
        }
    }
}

/* =============================================================================
 * 按钮 —— 54dp 高,squircle。Primary = 黑底白字;Accent = 绿底白字。
 * loading 态:呼吸点 + 等宽 connecting…
 * ========================================================================== */
enum class OcButtonStyle { Primary, Accent }

@Composable
fun OcButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: OcButtonStyle = OcButtonStyle.Primary,
    loading: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = LocalOcColors.current
    val bg = if (style == OcButtonStyle.Accent) c.accent else c.ink
    val fg = if (style == OcButtonStyle.Accent) c.accentInk else c.bg
    Box(
        modifier
            .fillMaxWidth()
            .height(54.dp)
            .pressable(enabled = !loading, onClick = onClick)
            .background(bg, OcButtonShape),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                OnlineDot()
                Text("connecting…", style = OcType.mono, color = fg)
            } else {
                leading?.invoke()
                Text(text, style = OcType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = fg)
            }
        }
    }
}
