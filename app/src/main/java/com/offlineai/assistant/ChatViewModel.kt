package com.offlineai.assistant

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class ChatMessage(val role: String, val text: String, val stats: String = "")

enum class ModelStatus { NONE, BUSY, LOADING, READY }

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    val messages = mutableStateListOf<ChatMessage>()

    var status by mutableStateOf(ModelStatus.NONE)
        private set
    var statusText by mutableStateOf("")
        private set
    /** 0..1 while the size is known, -1 when unknown. */
    var progress by mutableStateOf(-1f)
        private set
    var generating by mutableStateOf(false)
        private set
    var modelName by mutableStateOf("")
        private set

    private val modelsDir = File(app.filesDir, "models").apply { mkdirs() }
    private val prefs = app.getSharedPreferences("offlineai", Context.MODE_PRIVATE)
    private val nCtx = 4096
    private val nThreads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)
    private var loadedPath: String? = null

    init {
        autoLoad()
    }

    // ---------------------------------------------------------------- model files

    private fun autoLoad() {
        val last = prefs.getString("model", null)
        val file = last?.let { File(modelsDir, it) }?.takeIf { it.exists() }
            ?: modelsDir.listFiles()?.firstOrNull { it.name.endsWith(".gguf") }
        if (file != null) {
            loadModel(file)
        } else {
            status = ModelStatus.NONE
            statusText = "No model yet."
        }
    }

    private fun loadModel(file: File) {
        status = ModelStatus.LOADING
        statusText = "Loading ${file.name}..."
        progress = -1f
        viewModelScope.launch(Dispatchers.Default) {
            val error = try {
                NativeBridge.loadModel(file.absolutePath, nCtx, nThreads)
            } catch (t: Throwable) {
                "Native error: ${t.message}"
            }
            if (error.isEmpty()) {
                loadedPath = file.absolutePath
                modelName = file.name
                prefs.edit().putString("model", file.name).apply()
                statusText = ""
                status = ModelStatus.READY
            } else {
                loadedPath = null
                modelName = ""
                statusText = error
                status = ModelStatus.NONE
            }
        }
    }

    private fun fail(message: String) {
        status = if (loadedPath != null) ModelStatus.READY else ModelStatus.NONE
        statusText = message
        progress = -1f
    }

    private fun copyWithProgress(input: InputStream, out: OutputStream, total: Long, label: String) {
        val buf = ByteArray(256 * 1024)
        var done = 0L
        var lastUi = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            val now = System.currentTimeMillis()
            if (now - lastUi > 400) {
                lastUi = now
                val doneMb = done / 1_048_576
                if (total > 0) {
                    progress = done.toFloat() / total.toFloat()
                    statusText = "$label... $doneMb / ${total / 1_048_576} MB"
                } else {
                    progress = -1f
                    statusText = "$label... $doneMb MB"
                }
            }
        }
        out.flush()
    }

    fun downloadDefaultModel() {
        if (generating || status == ModelStatus.BUSY || status == ModelStatus.LOADING) return
        status = ModelStatus.BUSY
        statusText = "Starting download..."
        progress = 0f

        viewModelScope.launch(Dispatchers.IO) {
            val target = File(modelsDir, DEFAULT_MODEL_FILE)
            val part = File(modelsDir, "$DEFAULT_MODEL_FILE.part")
            var lastError = "Download failed."
            var done = false

            for (url in DEFAULT_MODEL_URLS) {
                var conn: HttpURLConnection? = null
                try {
                    conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 20_000
                    conn.readTimeout = 30_000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", "OfflineAI-Android")
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                        lastError = "The server answered HTTP ${conn.responseCode}."
                        continue
                    }
                    val total = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        part.outputStream().use { out ->
                            copyWithProgress(input, out, total, "Downloading")
                        }
                    }
                    if (total > 0 && part.length() != total) {
                        lastError = "The download was incomplete. Please try again."
                        continue
                    }
                    done = true
                    break
                } catch (e: Exception) {
                    lastError = e.message ?: "Download failed."
                } finally {
                    conn?.disconnect()
                }
            }

            if (done && part.renameTo(target)) {
                loadModel(target)
            } else {
                part.delete()
                fail(if (done) "Could not save the model file." else lastError)
            }
        }
    }

    fun importModel(uri: Uri) {
        if (generating || status == ModelStatus.BUSY || status == ModelStatus.LOADING) return
        status = ModelStatus.BUSY
        statusText = "Importing model..."
        progress = 0f

        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            var name = "model.gguf"
            var size = -1L
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameCol = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeCol = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameCol >= 0) c.getString(nameCol)?.let { name = it }
                    if (sizeCol >= 0 && !c.isNull(sizeCol)) size = c.getLong(sizeCol)
                }
            }
            if (!name.endsWith(".gguf", ignoreCase = true)) {
                fail("That file is not a .gguf model.")
                return@launch
            }

            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val target = File(modelsDir, safe)
            val part = File(modelsDir, "$safe.part")
            try {
                val input = resolver.openInputStream(uri)
                    ?: throw IllegalStateException("Could not open that file.")
                input.use { src ->
                    part.outputStream().use { out ->
                        copyWithProgress(src, out, size, "Importing")
                    }
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IllegalStateException("Could not save the model file.")
                loadModel(target)
            } catch (e: Exception) {
                part.delete()
                fail(e.message ?: "Import failed.")
            }
        }
    }

    // ---------------------------------------------------------------- chat

    fun newChat() {
        if (!generating) messages.clear()
    }

    fun stopGeneration() {
        NativeBridge.stop()
    }

    fun send(text: String) {
        val userText = text.trim()
        if (userText.isEmpty() || generating || status != ModelStatus.READY) return

        messages.add(ChatMessage("user", userText))
        messages.add(ChatMessage("assistant", ""))
        generating = true
        val history = messages.toList().dropLast(1)

        viewModelScope.launch(Dispatchers.Default) {
            val reply = StringBuilder()
            var pieces = 0
            val started = System.nanoTime()

            val listener = object : TokenListener {
                override fun onToken(bytes: ByteArray) {
                    reply.append(String(bytes, Charsets.UTF_8))
                    pieces++
                    messages[messages.lastIndex] = ChatMessage("assistant", reply.toString())
                }
            }

            val error = try {
                val prompt = buildPrompt(history)
                NativeBridge.generate(
                    prompt.toByteArray(Charsets.UTF_8),
                    MAX_NEW_TOKENS,
                    TEMPERATURE,
                    listener
                )
            } catch (t: Throwable) {
                "Native error: ${t.message}"
            }

            val seconds = (System.nanoTime() - started) / 1_000_000_000.0
            val stats = if (pieces > 0 && seconds > 0.0) {
                String.format(Locale.US, "~%.1f tokens/sec", pieces / seconds)
            } else ""
            var finalText = reply.toString()
            if (error.isNotEmpty()) finalText += "\n\n[Error] $error"
            if (finalText.isBlank()) finalText = "(no reply)"
            messages[messages.lastIndex] = ChatMessage("assistant", finalText, stats)
            generating = false
        }
    }

    /** ChatML format (used by Qwen and many other models). Other formats come later. */
    private fun format(msgs: List<ChatMessage>): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(SYSTEM_PROMPT).append("<|im_end|>\n")
        for (m in msgs) {
            sb.append("<|im_start|>").append(m.role).append("\n")
                .append(m.text).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    /** Drops the oldest turns until the conversation fits the context window. */
    private fun buildPrompt(history: List<ChatMessage>): String {
        var start = 0
        while (true) {
            val prompt = format(history.subList(start, history.size))
            val tokens = NativeBridge.tokenCount(prompt.toByteArray(Charsets.UTF_8))
            if (tokens <= nCtx - RESERVE_TOKENS || start >= history.size - 1) return prompt
            start += 2
        }
    }

    companion object {
        private const val SYSTEM_PROMPT =
            "You are a helpful offline assistant running on the user's phone. " +
                "Answer clearly and concisely."
        private const val MAX_NEW_TOKENS = 1024
        private const val RESERVE_TOKENS = 512
        private const val TEMPERATURE = 0.7f
        private const val DEFAULT_MODEL_FILE = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
        private val DEFAULT_MODEL_URLS = listOf(
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"
        )
    }
}
