package com.opencode.android.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.opencode.android.data.model.Message
import com.opencode.android.data.model.MessagePart
import com.opencode.android.ui.theme.*

@Composable
fun MessageBubble(message: Message) {
    val isUser = message.role == "user"

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = if (isUser) UserBubble else AssistantBubble,
            border = if (!isUser) CardDefaults.outlinedCardBorder() else null,
            modifier = Modifier.fillMaxWidth(0.88f)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        if (isUser) Icons.Default.Person else Icons.Default.SmartToy, null,
                        modifier = Modifier.size(16.dp), tint = if (isUser) Color.White else Primary
                    )
                    Text(
                        if (isUser) "You" else "OpenCode",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isUser) Color.White.copy(alpha = 0.8f) else Primary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                message.parts.forEach { part ->
                    when (part.type) {
                        "text" -> {
                            SelectionContainer { Text(part.text ?: "", style = MaterialTheme.typography.bodyMedium, color = if (isUser) Color.White else TextPrimary) }
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        "tool-invocation" -> ToolChip(part)
                        "tool-result" -> ToolResultChip(part)
                        "step" -> StepIndicator(part.text)
                    }
                }
                if (message.parts.isEmpty() && !message.content.isNullOrBlank()) {
                    SelectionContainer { Text(message.content!!, style = MaterialTheme.typography.bodyMedium, color = if (isUser) Color.White else TextPrimary) }
                }
            }
        }
    }
}

@Composable
fun StreamingBubble(text: String) {
    Surface(
        shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
        color = AssistantBubble, border = CardDefaults.outlinedCardBorder(),
        modifier = Modifier.fillMaxWidth(0.88f)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.SmartToy, null, modifier = Modifier.size(16.dp), tint = Primary)
                Text("OpenCode", style = MaterialTheme.typography.labelMedium, color = Primary)
            }
            Spacer(modifier = Modifier.height(8.dp))
            SelectionContainer { Text(text + "▌", style = MaterialTheme.typography.bodyMedium, color = TextPrimary) }
        }
    }
}

@Composable
private fun ToolChip(part: MessagePart) {
    Surface(shape = RoundedCornerShape(8.dp), color = SurfaceVariant) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.Build, null, modifier = Modifier.size(14.dp), tint = Warning)
            Text(part.toolName ?: "tool", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = Warning)
        }
    }
    Spacer(modifier = Modifier.height(6.dp))
}

@Composable
private fun ToolResultChip(part: MessagePart) {
    Surface(shape = RoundedCornerShape(8.dp), color = SurfaceVariant) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(14.dp), tint = Success)
            Text("${part.toolName ?: "tool"} done", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = Success)
        }
    }
    Spacer(modifier = Modifier.height(6.dp))
}

@Composable
private fun StepIndicator(text: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(12.dp), tint = Primary)
        Text(text ?: "thinking...", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = TextSecondary)
    }
    Spacer(modifier = Modifier.height(6.dp))
}

@Composable
fun EmptyChatState(onSuggestionClick: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Code, null, modifier = Modifier.size(64.dp), tint = Primary.copy(alpha = 0.6f))
        Spacer(modifier = Modifier.height(16.dp))
        Text("What would you like to build?", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Start a conversation with OpenCode", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Spacer(modifier = Modifier.height(24.dp))
        listOf("Explain this codebase" to Icons.Default.Explore, "Find bugs in my code" to Icons.Default.BugReport, "Write a new feature" to Icons.Default.AutoFixHigh, "Refactor this file" to Icons.Default.Architecture).forEach { (text, icon) ->
            SuggestionChip(onClick = { onSuggestionClick(text) }, label = { Text(text, style = MaterialTheme.typography.bodySmall) }, icon = { Icon(icon, null, modifier = Modifier.size(16.dp)) }, colors = SuggestionChipDefaults.suggestionChipColors(containerColor = SurfaceVariant, labelColor = TextPrimary, iconContentColor = Primary), border = null, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}
