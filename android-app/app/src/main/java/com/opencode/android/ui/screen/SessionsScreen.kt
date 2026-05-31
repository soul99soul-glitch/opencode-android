package com.opencode.android.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencode.android.data.api.OpenCodeApi
import com.opencode.android.data.model.Session
import com.opencode.android.data.repository.PreferencesRepository
import com.opencode.android.ui.theme.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(onSessionClick: (String, String?) -> Unit, onSettingsClick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PreferencesRepository(context) }

    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showNewDialog by remember { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            isLoading = true
            val cfg = prefs.config.first()
            val api = OpenCodeApi(cfg)
            api.listSessions().onSuccess { sessions = it; error = null }.onFailure { error = it.message }
            api.close()
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.Code, null, modifier = Modifier.size(24.dp), tint = Primary)
                        Text("OpenCode", style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = {
                    IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, "Refresh") }
                    IconButton(onClick = onSettingsClick) { Icon(Icons.Default.Settings, "Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showNewDialog = true }, containerColor = Primary, contentColor = Background, shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.Add, null); Spacer(modifier = Modifier.width(8.dp)); Text("New Chat")
            }
        },
        containerColor = Background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Primary)
                error != null -> Column(modifier = Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CloudOff, null, modifier = Modifier.size(48.dp), tint = Error)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(error ?: "Error", color = Error, style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { refresh() }, colors = ButtonDefaults.buttonColors(containerColor = Primary)) { Text("Retry") }
                }
                sessions.isEmpty() -> Column(modifier = Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ChatBubbleOutline, null, modifier = Modifier.size(64.dp), tint = TextMuted)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("No conversations yet", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Tap New Chat to get started", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(sessions, key = { it.id }) { session -> SessionCard(session) { onSessionClick(session.id, session.title) } }
                }
            }
        }
    }

    if (showNewDialog) {
        var title by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!isCreating) showNewDialog = false },
            title = { Text("New Chat") },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title (optional)") }, singleLine = true, shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Primary, cursorColor = Primary)) },
            confirmButton = {
                TextButton(onClick = {
                    isCreating = true
                    scope.launch {
                        val cfg = prefs.config.first()
                        val api = OpenCodeApi(cfg)
                        api.createSession(title.ifBlank { null })
                            .onSuccess { showNewDialog = false; onSessionClick(it.id, it.title) }
                            .onFailure { /* keep dialog open */ }
                        api.close()
                        isCreating = false
                    }
                }, enabled = !isCreating) { if (isCreating) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Primary) else Text("Create", color = Primary) }
            },
            dismissButton = { TextButton(onClick = { showNewDialog = false }, enabled = !isCreating) { Text("Cancel") } },
            containerColor = Surface
        )
    }
}

@Composable
private fun SessionCard(session: Session, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(14.dp), color = Surface, tonalElevation = 1.dp) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.Chat, null, tint = Primary, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(session.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextPrimary)
                session.time?.created?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = TextMuted) }
            }
            Icon(Icons.Default.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(20.dp))
        }
    }
}
