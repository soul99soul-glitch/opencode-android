package com.opencode.android.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.opencode.android.data.api.OpenCodeApi
import com.opencode.android.data.model.ServerConfig
import com.opencode.android.data.repository.PreferencesRepository
import com.opencode.android.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PreferencesRepository(context) }

    var host by remember { mutableStateOf("127.0.0.1") }
    var port by remember { mutableStateOf("4096") }
    var password by remember { mutableStateOf("") }
    var directory by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().background(Background).padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Code, null, modifier = Modifier.size(72.dp), tint = Primary)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Welcome to OpenCode", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
        Spacer(modifier = Modifier.height(6.dp))
        Text("Connect to your OpenCode server", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host") }, placeholder = { Text("127.0.0.1") }, singleLine = true, modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.Dns, null, tint = TextSecondary) }, shape = RoundedCornerShape(12.dp), colors = fc())
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), leadingIcon = { Icon(Icons.Default.SettingsEthernet, null, tint = TextSecondary) }, shape = RoundedCornerShape(12.dp), colors = fc())
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(), leadingIcon = { Icon(Icons.Default.Lock, null, tint = TextSecondary) }, trailingIcon = { IconButton(onClick = { passwordVisible = !passwordVisible }) { Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Toggle password visibility", tint = TextSecondary) } }, shape = RoundedCornerShape(12.dp), colors = fc())
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(value = directory, onValueChange = { directory = it }, label = { Text("Project Directory") }, placeholder = { Text("/home/user/project") }, singleLine = true, modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.Folder, null, tint = TextSecondary) }, shape = RoundedCornerShape(12.dp), colors = fc())
        Spacer(modifier = Modifier.height(24.dp))

        if (error != null) {
            Surface(shape = RoundedCornerShape(10.dp), color = Error.copy(alpha = 0.15f)) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.ErrorOutline, null, tint = Error, modifier = Modifier.size(18.dp))
                    Text(error ?: "", color = Error, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        Button(
            onClick = {
                error = null; isConnecting = true
                scope.launch {
                    val config = ServerConfig(host, port.toIntOrNull() ?: 4096, password, directory)
                    val api = OpenCodeApi(config)
                    val result = api.health()
                    api.close()
                    isConnecting = false
                    result.onSuccess {
                        if (it.healthy) { prefs.saveConfig(config); prefs.setSetupDone(true); onComplete() }
                        else error = "Server unhealthy"
                    }.onFailure { error = "Connection failed: ${it.message}" }
                }
            },
            enabled = host.isNotBlank() && !isConnecting,
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary, disabledContainerColor = SurfaceVariant)
        ) {
            if (isConnecting) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Background)
            else { Icon(Icons.Default.PowerSettingsNew, null); Spacer(modifier = Modifier.width(8.dp)); Text("Connect", style = MaterialTheme.typography.titleMedium) }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Surface(shape = RoundedCornerShape(10.dp), color = SurfaceVariant.copy(alpha = 0.5f)) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Info, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                Text("Run 'opencode serve' on your computer, or use the local server built into this app.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun fc() = OutlinedTextFieldDefaults.colors(focusedBorderColor = Primary, unfocusedBorderColor = Border, focusedContainerColor = SurfaceVariant.copy(alpha = 0.3f), unfocusedContainerColor = SurfaceVariant.copy(alpha = 0.3f), cursorColor = Primary, focusedLabelColor = Primary, unfocusedLabelColor = TextMuted)
