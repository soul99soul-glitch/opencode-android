package com.opencode.android.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import com.opencode.android.ui.theme.LocalOcColors
import com.opencode.android.ui.theme.OcType

/**
 * Plain 风格的 Markdown 渲染组件。
 *
 * 支持: **粗体** / *斜体* / `行内代码` / ```代码块``` / 列表 / 标题 / 链接 / 引用 / 删除线
 * 样式统一走 OcColors + OcType。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = OcType.body,
) {
    val c = LocalOcColors.current

    val state = rememberMarkdownState(text)

    Markdown(
        markdownState = state,
        modifier = modifier,
        colors = markdownColor(
            text = c.ink,
            codeBackground = c.codeBg,
            inlineCodeBackground = c.codeBg,
            dividerColor = c.line,
        ),
        typography = markdownTypography(
            h1 = OcType.titleL.copy(fontWeight = FontWeight.Bold),
            h2 = OcType.title.copy(fontWeight = FontWeight.Bold),
            h3 = OcType.rowTitle,
            h4 = OcType.bodyStrong,
            h5 = OcType.bodyStrong,
            h6 = OcType.secondary,
            text = style,
            code = OcType.code,
            paragraph = style,
        ),
    )
}
