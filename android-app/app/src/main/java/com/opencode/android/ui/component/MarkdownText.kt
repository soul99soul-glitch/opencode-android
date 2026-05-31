package com.opencode.android.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import com.opencode.android.ui.theme.LocalOcColors
import com.opencode.android.ui.theme.OcType

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = OcType.body,
    textColor: Color = LocalOcColors.current.ink,
    codeBg: Color = LocalOcColors.current.codeBg,
) {
    val c = LocalOcColors.current

    val state = rememberMarkdownState(text)

    Markdown(
        markdownState = state,
        modifier = modifier,
        colors = markdownColor(
            text = textColor,
            codeBackground = codeBg,
            inlineCodeBackground = codeBg,
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
