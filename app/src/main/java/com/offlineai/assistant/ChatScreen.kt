package com.offlineai.assistant

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ChatScreen(vm: ChatViewModel) {
    var input by remember { mutableStateOf("") }
    var showModelPanel by remember { mutableStateOf(false) }
    val ready = vm.status == ModelStatus.READY
    val listState = rememberLazyListState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.importModel(uri)
    }
    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.attachDocument(uri)
    }

    // Keep the newest text in view while a reply streams in.
    val lastLength = vm.messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(vm.messages.size, lastLength) {
        if (vm.messages.isNotEmpty()) {
            listState.scrollToItem(vm.messages.size - 1)
            listState.scrollBy(100000f)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {

            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Offline AI", style = MaterialTheme.typography.titleLarge)
                    if (ready && vm.modelName.isNotEmpty()) {
                        Text(
                            vm.modelName,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                }
                TextButton(onClick = { showModelPanel = !showModelPanel }) { Text("Model") }
                TextButton(onClick = { vm.newChat() }, enabled = !vm.generating) { Text("New chat") }
            }

            if (!ready || showModelPanel) {
                ModelPanel(
                    vm = vm,
                    ready = ready,
                    onImport = { picker.launch(arrayOf("*/*")) },
                    onClose = { showModelPanel = false }
                )
            }

            // Messages
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (vm.messages.isEmpty() && ready) {
                    Text(
                        "Ask anything, or tap Attach to add a PDF or text file " +
                            "and ask questions about it.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp)
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(vm.messages) { m -> MessageBubble(m) }
                }
            }

            DocBar(vm)

            // Input row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                TextButton(
                    onClick = { docPicker.launch(arrayOf("application/pdf", "text/*")) },
                    enabled = !vm.generating && vm.docStatus.isEmpty()
                ) { Text("Attach") }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            if (!ready) "Set up a model first"
                            else if (vm.document != null) "Ask about the document"
                            else "Message"
                        )
                    },
                    enabled = ready,
                    maxLines = 5
                )
                Spacer(Modifier.width(8.dp))
                if (vm.generating) {
                    Button(onClick = { vm.stopGeneration() }) { Text("Stop") }
                } else {
                    Button(
                        onClick = {
                            vm.send(input)
                            input = ""
                        },
                        enabled = ready && input.isNotBlank()
                    ) { Text("Send") }
                }
            }
        }
    }
}

@Composable
private fun ModelPanel(
    vm: ChatViewModel,
    ready: Boolean,
    onImport: () -> Unit,
    onClose: () -> Unit
) {
    val busy = vm.status == ModelStatus.BUSY || vm.status == ModelStatus.LOADING
    val cpuInfo = remember {
        try {
            NativeBridge.systemInfo().trim()
        } catch (t: Throwable) {
            "native library not loaded"
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (ready) "Model ready" else "Set up a model",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (vm.statusText.isNotEmpty()) vm.statusText
                else "Download a model once. After that everything runs offline.",
                style = MaterialTheme.typography.bodySmall
            )
            if (busy) {
                Spacer(Modifier.height(8.dp))
                if (vm.progress >= 0f) {
                    LinearProgressIndicator(
                        progress = { vm.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.downloadDefaultModel() },
                enabled = !busy && !vm.generating,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Download Qwen 1.5B (about 1 GB)") }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = onImport,
                enabled = !busy && !vm.generating,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Import a .gguf file") }
            Spacer(Modifier.height(8.dp))
            Text("CPU: $cpuInfo", style = MaterialTheme.typography.labelSmall)
            if (ready) {
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}

@Composable
private fun MessageBubble(m: ChatMessage) {
    val isUser = m.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 340.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                SelectionContainer {
                    Text(m.text.ifEmpty { "..." })
                }
                if (m.stats.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(m.stats, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun DocBar(vm: ChatViewModel) {
    val doc = vm.document
    if (doc != null || vm.docStatus.isNotEmpty() || vm.docError.isNotEmpty()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (vm.docStatus.isNotEmpty()) {
                    Text(vm.docStatus, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else if (doc != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(doc.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            val size = if (doc.pages > 0) "${doc.pages} pages" else "text file"
                            Text(
                                "$size, ${doc.words} words",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        TextButton(
                            onClick = { vm.removeDocument() },
                            enabled = !vm.generating
                        ) { Text("Remove") }
                    }
                }
                if (vm.docError.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            vm.docError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { vm.clearDocError() }) { Text("OK") }
                    }
                }
            }
        }
    }
}
