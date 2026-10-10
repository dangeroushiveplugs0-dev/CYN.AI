package com.cyn.ai

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class ChatMessage(val text: String, val fromUser: Boolean, val modelContext: String? = null, val attachmentUri: Uri? = null)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFB7F36B),
                    onPrimary = Color(0xFF15200B),
                    background = Color(0xFF101112),
                    surface = Color(0xFF191B1E),
                    onSurface = Color(0xFFF0F0F0)
                )
            ) {
                CynHome()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CynHome() {
    var draft by remember { mutableStateOf("") }
    var pickedFile by remember { mutableStateOf<Uri?>(null) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    var showModels by remember { mutableStateOf(false) }
    var libraryMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val library = remember { ModelLibrary(context) }
    val models = remember { mutableStateListOf<LocalModel>().apply { addAll(library.list()) } }
    val messages = remember {
        mutableStateListOf(
            ChatMessage("CYN is ready for its first build. Local model inference is the next integration milestone.", false)
        )
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val displayName = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && column >= 0) cursor.getString(column) else null
                }
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Selected file"
            if (displayName.lowercase().endsWith(".gguf")) {
                runCatching { library.register(uri) }
                    .onSuccess { model ->
                        models.removeAll { it.uri == model.uri }
                        models.add(model)
                        libraryMessage = "Registered ${model.name}. Local inference is not connected yet."
                    }
                    .onFailure { libraryMessage = "Couldn't keep access to this file. Select it again." }
            } else {
                pickedFile = uri
                pickedName = displayName
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .windowInsetsPadding(WindowInsets.ime)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("CYN", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Text("LOCAL AI • ANDROID", fontSize = 10.sp, color = Color(0xFFB7F36B), letterSpacing = 1.5.sp)
                }
                IconButton(onClick = { showModels = !showModels }) {
                    Icon(Icons.Default.Memory, contentDescription = "Model manager")
                }
            }

            if (showModels) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("MODEL LIBRARY", fontWeight = FontWeight.Bold, color = Color(0xFFB7F36B))
                        Text("No model is bundled. Import a GGUF from your phone storage.")
                        Button(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("Import model or file")
                        }
                        Text("Coding: Qwen2.5-Coder 3B Q8_0", fontSize = 12.sp)
                        Text("Vision: SmolVLM2 500M F16 / 2.2B Q6_K", fontSize = 12.sp)
                        if (models.isEmpty()) {
                            Text("No GGUF models registered yet.", fontSize = 12.sp, color = Color.LightGray)
                        }
                        models.forEach { model ->
                            Row(
                                modifier = Modifier.fillMaxWidth().background(Color(0xFF24282A), RoundedCornerShape(10.dp)).padding(start = 10.dp, end = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                    Text(model.name, fontSize = 12.sp)
                                    Text(model.sizeLabel, fontSize = 10.sp, color = Color.LightGray)
                                }
                                IconButton(onClick = {
                                    library.remove(model)
                                    models.remove(model)
                                    libraryMessage = "Removed from CYN's list; original file was not deleted."
                                }) { Text("×", fontSize = 18.sp) }
                            }
                        }
                        Text("Registered models are remembered, but not loaded for inference yet.", fontSize = 11.sp, color = Color.LightGray)
                        libraryMessage?.let { Text(it, fontSize = 11.sp, color = Color(0xFFB7F36B)) }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(messages) { message ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.88f)
                                .background(
                                    if (message.fromUser) Color(0xFF28351F) else Color(0xFF1B1D20),
                                    RoundedCornerShape(18.dp)
                                )
                                .padding(14.dp)
                        ) {
                            Text(message.text, color = Color(0xFFF0F0F0), lineHeight = 21.sp)
                        }
                    }
                }
            }

            if (pickedName != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF24282A))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.AttachFile, contentDescription = null, tint = Color(0xFFB7F36B))
                        Text(pickedName ?: "Attachment", modifier = Modifier.weight(1f), fontSize = 12.sp)
                        IconButton(onClick = { pickedFile = null; pickedName = null }) {
                            Text("×", fontSize = 20.sp)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF1B1D20), RoundedCornerShape(24.dp)).padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { filePicker.launch(arrayOf("image/*")) }) {
                    Icon(Icons.Default.Image, contentDescription = "Attach image", tint = Color(0xFFB7F36B))
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message CYN…", fontSize = 14.sp) },
                    maxLines = 5,
                    shape = RoundedCornerShape(20.dp)
                )
                IconButton(
                    onClick = {
                        val text = draft.trim()
                        if (text.isNotEmpty() || pickedName != null) {
                            val attachmentUri = pickedFile
                            val inspection = attachmentUri?.let { uri ->
                                runCatching { AttachmentInspector.inspect(context, uri) }.getOrNull()
                            }
                            val combined = if (pickedName != null) {
                                (if (text.isNotEmpty()) "$text\n" else "") + "Attachment: $pickedName"
                            } else text
                            messages.add(ChatMessage(combined, true, inspection?.toModelContext(), attachmentUri))
                            val reply = when {
                                inspection == null && pickedName != null ->
                                    "I couldn't inspect this attachment. Check that the file is still accessible and try attaching it again. The local AI runtime is not connected yet."
                                inspection != null -> buildString {
                                    appendLine("File inspection: ${inspection.name}")
                                    appendLine("Type: ${inspection.kind} • ${inspection.mimeType}")
                                    appendLine(inspection.summary)
                                    if (inspection.extractedText.isNotBlank()) {
                                        appendLine()
                                        appendLine("Extracted preview:")
                                        appendLine(inspection.extractedText.take(1800))
                                    }
                                    appendLine()
                                    append("This is local file inspection, not an AI-generated interpretation yet. The attachment metadata/context is now available for the inference integration; visual, audio, PDF, and complex 3D understanding need their compatible parsers/models.")
                                }
                                else -> "The chat UI is active, but the local inference runtime is not connected yet."
                            }
                            messages.add(ChatMessage(reply, false))
                            draft = ""
                            pickedFile = null
                            pickedName = null
                        }
                    }
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send message", tint = Color(0xFFB7F36B))
                }
            }
            Text(
                "LOCAL-FIRST • MODEL RUNS ON DEVICE WHEN ENABLED",
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
                fontSize = 9.sp,
                color = Color(0xFF888B8F),
                letterSpacing = 0.7.sp
            )
        }
    }
}
