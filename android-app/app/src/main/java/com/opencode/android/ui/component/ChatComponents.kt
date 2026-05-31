package com.opencode.android.ui.component

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
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
import com.opencode.android.data.model.Message
import com.opencode.android.data.model.MessagePart
import com.opencode.android.ui.theme.LocalOcColors
import com.opencode.android.ui.theme.OcUserBubbleShape
import com.opencode.android.ui.theme.OcType
import kotlin.math.roundToInt

@Composable
fun MessageBubble(message: Message) {
    val isUser = message.info.role == "user"
    val c = LocalOcColors.current

    // Separate text from file/image parts
    val textParts = message.parts.filter { it.type == "text" }
    val mediaParts = message.parts.filter { it.type == "file" || it.type == "image" }

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (isUser) {
            // ── Text bubble (dark, right-aligned) ──
            if (textParts.isNotEmpty()) {
                Box(
                    Modifier
                        .widthIn(max = 310.dp)
                        .background(c.userBg, OcUserBubbleShape)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Column {
                        textParts.forEach { part ->
                            MarkdownText(
                                text = part.text ?: "",
                                style = OcType.body.copy(color = c.userInk),
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }

            // ── Media attachments below bubble ──
            mediaParts.forEach { part ->
                val isImage = part.mime?.startsWith("image/") == true
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
                    val name = part.filename ?: part.text ?: "file"
                    FileCapsule(name)
                }
            }

            Spacer(Modifier.height(4.dp))
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

// ── User image attachment: rendered below bubble, tap for full-screen ──

@Composable
private fun UserImageAttachment(bitmap: androidx.compose.ui.graphics.ImageBitmap) {
    var showOverlay by remember { mutableStateOf(false) }

    Image(
        bitmap, "attached image",
        Modifier
            .padding(top = 6.dp)
            .widthIn(max = 280.dp)
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
    val offsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
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
                            scope.launch {
                                if (kotlin.math.abs(offsetY.value) > dismissThresholdPx) {
                                    onDismiss()
                                } else {
                                    offsetY.animateTo(0f, spring())
                                }
                            }
                        },
                        onVerticalDrag = { _, dragAmount ->
                            scope.launch {
                                offsetY.snapTo(offsetY.value + dragAmount)
                            }
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
                        translationY = offsetY.value
                        alpha = 1f - (kotlin.math.abs(offsetY.value) / (dismissThresholdPx * 2)).coerceIn(0f, 1f)
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
