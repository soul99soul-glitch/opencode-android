package com.opencode.android.ui.component

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.opencode.android.data.model.Message
import com.opencode.android.data.model.MessagePart
import com.opencode.android.ui.component.MarkdownText
import com.opencode.android.ui.theme.LocalOcColors
import com.opencode.android.ui.theme.OcBubbleShape
import com.opencode.android.ui.theme.OcType
import com.opencode.android.ui.theme.OcUserBubbleShape

@Composable
fun MessageBubble(message: Message) {
    val isUser = message.info.role == "user"
    val c = LocalOcColors.current

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (isUser) {
            // User: right-aligned dark bubble with asymmetric corners
            Box(
                Modifier
                    .fillMaxWidth(0.88f)
                    .background(c.userBg, OcUserBubbleShape)
                    .padding(14.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Column {
                    message.parts.forEach { part ->
                        when (part.type) {
                            "text" -> {
                                MarkdownText(
                                    text = part.text ?: "",
                                    style = OcType.body.copy(color = c.userInk),
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                            else -> { /* skip non-text in user bubbles */ }
                        }
                    }
                }
            }
        } else {
            // Assistant: ZERO decoration — no background, no bubble, just content
            Column(Modifier.fillMaxWidth(0.92f)) {
                // Signature line
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text("</>", style = OcType.mono.copy(fontSize = OcType.mono.fontSize), color = c.accent)
                    Text("opencode", style = OcType.monoStrong, color = c.ink3)
                }
                Spacer(Modifier.height(8.dp))

                message.parts.forEach { part ->
                    when (part.type) {
                        "text" -> {
                            MarkdownText(
                                text = part.text ?: "",
                                style = OcType.body,
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        "tool" -> {
                            val toolName = part.tool ?: "tool"
                            val inputObj = part.state?.input
                            // Build a short arg description from input fields
                            val arg = inputObj?.entries?.firstOrNull()?.value?.toString()?.trim('"')?.take(60)
                                ?: ""
                            val status = part.state?.status ?: ""
                            // Format full input for detail view
                            val inputDetail = inputObj?.entries?.joinToString("\n") { (k, v) ->
                                "$k: ${v.toString().trim('"')}"
                            }
                            ToolCallRow(
                                tool = toolName,
                                arg = arg,
                                status = if (status == "completed") "done" else status,
                                output = part.state?.output?.take(2000),
                                input = inputDetail,
                            )
                            Spacer(Modifier.height(3.dp))
                        }
                        "tool-invocation" -> ToolCallRow(
                            tool = part.type,
                            arg = part.text ?: "",
                            status = "run",
                        )
                        "tool-result" -> ToolCallRow(
                            tool = part.type,
                            arg = part.text ?: "",
                            status = "done",
                        )
                        "step-start" -> { /* skip */ }
                        "step-finish" -> { /* skip */ }
                        "reasoning" -> {
                            val text = part.text ?: ""
                            if (text.isNotBlank()) {
                                ThinkingBlock(text = text, chars = text.length)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StreamingBubble(text: String) {
    val c = LocalOcColors.current

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        // Assistant streaming: ZERO decoration — no background, no bubble
        Column(Modifier.fillMaxWidth(0.92f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text("</>", style = OcType.mono, color = c.accent)
                Text("opencode", style = OcType.monoStrong, color = c.ink3)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                MarkdownText(
                    text = text,
                    modifier = Modifier.weight(1f),
                    style = OcType.body,
                )
                BlinkingCursor()
            }
        }
    }
}

@Composable
fun EmptyChatState(onSuggestionClick: (String) -> Unit) {
    val c = LocalOcColors.current

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("opencode", style = OcType.brand, color = c.ink)
            BlinkingCursor(color = c.accent)
        }
        Spacer(Modifier.height(20.dp))
        Text("What would you like to build?", style = OcType.titleL, color = c.ink)
        Spacer(Modifier.height(28.dp))

        val suggestions = listOf(
            "Explain this codebase",
            "Find bugs in my code",
            "Write a new feature",
        )
        suggestions.forEach { text ->
            Box(
                Modifier
                    .padding(vertical = 4.dp)
                    .pressable { onSuggestionClick(text) }
                    .background(c.surface2, RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(text, style = OcType.secondary, color = c.ink2)
            }
        }
    }
}
