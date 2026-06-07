package com.opencode.android.ui.component

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.opencode.android.workspace.SafWorkspaceBridge

data class SafFolderSelection(
    val treeUri: String,
    val displayName: String,
)

@Composable
fun rememberSafFolderPicker(
    onSelected: (SafFolderSelection) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val permission = SafWorkspaceBridge.takePersistablePermission(context, uri)
        if (permission.isFailure) return@rememberLauncherForActivityResult
        val displayName = SafWorkspaceBridge.displayName(context, uri) ?: "folder"
        onSelected(
            SafFolderSelection(
                treeUri = uri.toString(),
                displayName = displayName,
            ),
        )
    }
    return {
        val initialUri = DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents",
            "primary:${Environment.DIRECTORY_DOCUMENTS}",
        )
        launcher.launch(initialUri)
    }
}
