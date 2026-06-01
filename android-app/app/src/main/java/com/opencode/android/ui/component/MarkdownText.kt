package com.opencode.android.ui.component

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.State
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
    contentKey: Any? = null,
) {
    val c = LocalOcColors.current

    key(contentKey ?: text) {
        val state = rememberMarkdownState(
            content = text,
            retainState = true,
        )
        val parsedState by state.state.collectAsState()
        var lastSuccessState by remember { mutableStateOf<State.Success?>(null) }
        LaunchedEffect(parsedState) {
            val success = parsedState as? State.Success
            if (success != null) {
                lastSuccessState = success
            }
        }

        val colors = markdownColor(
            text = textColor,
            codeBackground = codeBg,
            inlineCodeBackground = codeBg,
            dividerColor = c.line,
        )
        val typography = markdownTypography(
            h1 = OcType.titleL.copy(fontWeight = FontWeight.Bold),
            h2 = OcType.title.copy(fontWeight = FontWeight.Bold),
            h3 = OcType.rowTitle,
            h4 = OcType.bodyStrong,
            h5 = OcType.bodyStrong,
            h6 = OcType.secondary,
            text = style,
            code = OcType.code,
            paragraph = style,
        )

        val successState = (parsedState as? State.Success) ?: lastSuccessState
        if (successState != null) {
            Markdown(
                successState,
                modifier = modifier,
                colors = colors,
                typography = typography,
            )
        } else {
            Text(
                text = text,
                modifier = modifier,
                style = style.copy(color = textColor),
            )
        }
    }
}
