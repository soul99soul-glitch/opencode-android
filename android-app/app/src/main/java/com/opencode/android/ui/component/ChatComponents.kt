package com.opencode.android.ui.component

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.opencode.android.ui.screen.chat.ChatDisplayMessage
import com.opencode.android.ui.screen.chat.MessagePhase
import com.opencode.android.ui.theme.LocalOcColors
import com.opencode.android.ui.theme.OcUserBubbleShape
import com.opencode.android.ui.theme.OcType

@Composable
fun MessageBubble(
    message: ChatDisplayMessage,
    onSubagentClick: ((sessionId: String, agentName: String) -> Unit)? = null,
) {
    val isUser = message.role == "user"
    val c = LocalOcColors.current

    fun partHasVisibleContent(type: String, text: String?): Boolean {
        return when (type) {
            "text", "reasoning" -> !text.isNullOrBlank()
            "file", "image", "tool", "tool-invocation", "tool-result" -> true
            else -> false
        }
    }

    // Separate text from file/image parts
    val textParts = message.visibleParts.filter { it.type == "text" && !it.text.isNullOrBlank() }
    val mediaParts = message.visibleParts.filter { it.type == "file" || it.type == "image" }
    val hasVisiblePartContent = message.visibleParts.any { part ->
        partHasVisibleContent(part.type, part.text)
    }
    val hasActiveStreamingSlot = !isUser && message.phase == MessagePhase.Streaming
    if (!hasVisiblePartContent && !hasActiveStreamingSlot) return

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (isUser) {
            // ── Agent tag (shown when sent to a specific subagent) ──
            val agentTag = message.agent?.takeIf { it != "orchestrator" && it != "build" && it != "plan" }
            if (agentTag != null) {
                Text(
                    "→ @$agentTag",
                    style = OcType.mono.copy(fontSize = 11.sp),
                    color = c.accent,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            // ── Text bubble (dark, right-aligned) ──
            if (textParts.isNotEmpty()) {
                Box(
                    Modifier
                        .widthIn(max = 310.dp)
                        .background(c.userBg, OcUserBubbleShape)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    SelectionContainer {
                        Column {
                            textParts.forEachIndexed { textIndex, part ->
                                val isUserBgDark = (c.userBg.red + c.userBg.green + c.userBg.blue) / 3f < 0.5f
                                val userCodeBg = if (isUserBgDark) {
                                    c.userBg.copy(
                                        red = (c.userBg.red + 0.08f).coerceAtMost(1f),
                                        green = (c.userBg.green + 0.08f).coerceAtMost(1f),
                                        blue = (c.userBg.blue + 0.08f).coerceAtMost(1f),
                                    )
                                } else {
                                    c.userBg.copy(
                                        red = (c.userBg.red - 0.06f).coerceAtLeast(0f),
                                        green = (c.userBg.green - 0.06f).coerceAtLeast(0f),
                                        blue = (c.userBg.blue - 0.06f).coerceAtLeast(0f),
                                    )
                                }
                                MarkdownText(
                                    text = part.text ?: "",
                                    style = OcType.body.copy(color = c.userInk),
                                    textColor = c.userInk,
                                    codeBg = userCodeBg,
                                    contentKey = "${message.renderId}:${part.renderId}",
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }

            // ── Media attachments below bubble ──
            mediaParts.forEach { part ->
                val isImage = part.type == "image"
                    || part.mime?.startsWith("image/") == true
                    || (part.url?.contains("base64,") == true && part.mime == null)
                if (isImage) {
                    val bitmap = remember(part.url, part.text) {
                        try {
                            val dataUri = part.url ?: part.text ?: ""
                            val base64Data = dataUri.substringAfter("base64,")
                            if (base64Data.isNotEmpty()) {
                                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                            } else null
                        } catch (_: Exception) { null }
                    }
                    if (bitmap != null) {
                        UserImageAttachment(bitmap)
                    }
                } else {
                    val name = part.filename ?: part.mime ?: "file"
                    FileCapsule(name)
                }
            }

            Spacer(Modifier.height(4.dp))
        } else {
            // Assistant: ZERO decoration — no background, no bubble, just content
            Column(Modifier.fillMaxWidth(0.92f)) {
                // Signature line — shows agent name when routed to subagent
                val agentName = message.agent
                val isSub = agentName != null && agentName != "orchestrator"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text("</>", style = OcType.mono.copy(fontSize = OcType.mono.fontSize), color = c.accent)
                    Text(
                        if (isSub) "@$agentName" else "opencode",
                        style = OcType.monoStrong,
                        color = if (isSub) c.accent else c.ink3,
                    )
                }
                Spacer(Modifier.height(8.dp))

                var textIndex = 0
                val orderedParts = message.visibleParts.sortedBy { it.sourceOrder }
                orderedParts.forEach { part ->
                    when (part.type) {
                        "text" -> {
                            val currentTextIndex = textIndex++
                            val text = part.text.orEmpty()
                            if (text.isBlank()) {
                                if (message.phase == MessagePhase.Streaming) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        OnlineDot()
                                        Text("Thinking…", style = OcType.mono, color = c.ink3)
                                    }
                                    Spacer(Modifier.height(6.dp))
                                }
                            } else {
                                SelectionContainer {
                                    MarkdownText(
                                        text = text,
                                        style = OcType.body,
                                        contentKey = "${message.renderId}:${part.renderId}:$currentTextIndex",
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                        "tool" -> {
                            val toolName = part.tool ?: "tool"
                            val inputObj = part.state?.input
                            val subagentType = inputObj?.get("subagent_type")?.toString()?.trim('"')
                            // Metadata.sessionId = actual subtask session ID
                            // Top-level part.sessionID = parent session ID
                            val subSid = part.state?.metadata?.get("sessionId")?.toString()?.trim('"')
                                ?: part.sessionID

                            // Task/subtask tool → capsule (with agent name if available)
                            if (toolName == "task" || toolName == "subtask") {
                                val agentLabel = subagentType ?: toolName
                                SubagentCapsule(
                                    agent = agentLabel,
                                    status = part.state?.status ?: "",
                                    onClick = if (onSubagentClick != null)
                                        {{ onSubagentClick(subSid ?: "", agentLabel) }}
                                    else null,
                                )
                                Spacer(Modifier.height(3.dp))
                            } else {
                                val arg = inputObj?.entries?.firstOrNull()?.value?.toString()?.trim('"')?.take(60)
                                    ?: ""
                                val status = part.state?.status ?: ""
                                val inputDetail = inputObj?.entries?.joinToString("\n") { (k, v) ->
                                    "$k: ${v.toString().trim('"')}"
                                }
                                ToolCallRow(
                                    tool = toolName,
                                    arg = arg,
                                    status = if (status == "completed") "done" else status,
                                    output = part.state?.output,
                                    input = inputDetail,
                                )
                                Spacer(Modifier.height(3.dp))
                            }
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

// ── User image attachment: rendered below bubble, tap for full-screen ──

@Composable
private fun UserImageAttachment(bitmap: androidx.compose.ui.graphics.ImageBitmap) {
    var showOverlay by remember { mutableStateOf(false) }

    Image(
        bitmap, "attached image",
        Modifier
            .padding(top = 6.dp)
            .widthIn(max = 140.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { showOverlay = true },
        contentScale = ContentScale.FillWidth,
    )

    if (showOverlay) {
        ImageOverlay(bitmap) { showOverlay = false }
    }
}

// ── Full-screen image overlay with swipe to dismiss ──

@Composable
private fun ImageOverlay(
    bitmap: androidx.compose.ui.graphics.ImageBitmap,
    onDismiss: () -> Unit,
) {
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 150.dp.toPx() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { onDismiss() }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (kotlin.math.abs(dragOffset) > dismissThresholdPx) {
                                onDismiss()
                            } else {
                                dragOffset = 0f
                            }
                        },
                        onVerticalDrag = { _, dragAmount ->
                            dragOffset += dragAmount
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap, null,
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = dragOffset
                        alpha = 1f - (kotlin.math.abs(dragOffset) / (dismissThresholdPx * 2)).coerceIn(0f, 1f)
                    },
                contentScale = ContentScale.Fit,
            )
        }
    }
}

// ── File attachment capsule: icon + truncated filename ──

@Composable
fun FileCapsule(filename: String, modifier: Modifier = Modifier) {
    val c = LocalOcColors.current
    Row(
        modifier
            .padding(top = 6.dp)
            .widthIn(max = 240.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(c.surface2)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(
            Icons.Outlined.Description, "file",
            tint = c.ink3,
            modifier = Modifier.size(16.dp),
        )
        Text(
            filename,
            style = OcType.mono.copy(fontSize = 11.sp),
            color = c.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

// ── Subagent capsule: clickable pill showing agent name ──

private fun toShortAgent(agent: String): String = when (agent) {
    "orchestrator" -> "orch"
    "designer"    -> "dsn"
    "fixer"       -> "fix"
    "explorer"    -> "exp"
    "librarian"   -> "lib"
    "oracle"      -> "ora"
    "councillor"  -> "cou"
    "code"        -> "code"
    "plan"        -> "plan"
    "build"       -> "build"
    "subtask"     -> "sub"
    else          -> agent.take(4)
}

@Composable
private fun SubagentCapsule(agent: String, status: String, onClick: (() -> Unit)?) {
    val c = LocalOcColors.current
    val short = toShortAgent(agent)
    val isRunning = status == "running" || status == "queued"

    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(c.accent.copy(alpha = 0.10f))
            .then(if (onClick != null) Modifier.pressable { onClick() } else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (isRunning) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(c.accent, RoundedCornerShape(3.dp)),
            )
        }
        Text(
            "@$short",
            style = OcType.mono.copy(fontSize = 11.sp),
            color = c.accent,
        )
        Text(
            if (isRunning) "running" else "done",
            style = OcType.mono.copy(fontSize = 10.sp),
            color = c.ink4,
        )
    }
}

@Composable
fun StreamingBubble(text: String, useMarkdown: Boolean = false) {
    val c = LocalOcColors.current

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Column(Modifier.fillMaxWidth(0.92f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text("</>", style = OcType.mono, color = c.accent)
                Text("opencode", style = OcType.monoStrong, color = c.ink3)
            }
            Spacer(Modifier.height(8.dp))
            if (useMarkdown) {
                // Pre-render as markdown during transition window — matches final MessageBubble layout
                MarkdownText(text = text, style = OcType.body)
            } else {
                // Plain Text during active streaming — cheap, no re-parse per delta
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = text,
                        modifier = Modifier.weight(1f),
                        style = OcType.body,
                        color = c.ink,
                    )
                    BlinkingCursor()
                }
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
