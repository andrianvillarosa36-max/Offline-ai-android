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
import kotlin.math.roundToInt

data class ChatMessage(val role: String, val text: String, val stats: String = "")

enum class ModelStatus { NONE, BUSY, LOADING, READY }

/** A document the user attached, split into searchable chunks. */
class Doc(
    val name: String,
    val pages: Int,
    val words: Int,
    val chunks: List<String>,
    val index: Bm25Index,
    val text: String
) {
    /** Filled in after the first full summary, so follow-up questions are cheap. */
    var summary: String? = null
}

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

    var document by mutableStateOf<Doc?>(null)
        private set
    /** Non-empty while a document is being read. */
    var docStatus by mutableStateOf("")
        private set
    var docError by mutableStateOf("")
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

    // ---------------------------------------------------------------- documents

    fun attachDocument(uri: Uri) {
        if (generating || docStatus.isNotEmpty()) return
        docError = ""
        docStatus = "Opening document..."

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val extracted = DocReader.read(getApplication<Application>(), uri) { docStatus = it }
                if (extracted.text.isBlank()) {
                    throw IllegalStateException(
                        "No selectable text found. Scanned PDFs need OCR, which comes in a later update."
                    )
                }
                docStatus = "Indexing..."
                val chunks = Retrieval.chunk(extracted.text)
                val words = extracted.text.split(Regex("\\s+")).count { it.isNotEmpty() }
                document = Doc(extracted.name, extracted.pages, words, chunks, Bm25Index(chunks), extracted.text)
            } catch (t: Throwable) {
                docError = if (t is OutOfMemoryError) {
                    "Not enough memory to read this file."
                } else {
                    t.message ?: "Could not read that file."
                }
            } finally {
                docStatus = ""
            }
        }
    }

    fun removeDocument() {
        if (!generating) document = null
    }

    fun clearDocError() {
        docError = ""
    }

    /** Wraps the question with the most relevant excerpts of the attached document. */
    private fun isGeneral(question: String): Boolean {
        val q = question.lowercase().replace('\u2019', '\'')
        return GENERAL_HINTS.any { q.contains(it) }
    }

    private fun docPrompt(doc: Doc, question: String): String {
        if (isGeneral(question)) {
            val cached = doc.summary
            if (cached != null) {
                return "Here is a summary of a document:\n\n" + cached +
                    "\n\nUsing only this summary, answer the request: " + question
            }
            val n = minOf(2, doc.chunks.size)
            val sb = StringBuilder("Here is the beginning of a document:\n\n")
            for (i in 0 until n) {
                sb.append("[Excerpt ").append(i + 1).append("]\n").append(doc.chunks[i]).append("\n\n")
            }
            sb.append("Using it, answer the request: ").append(question)
            sb.append("\nMention that this is based only on the beginning of the document.")
            return sb.toString()
        }
        var picks = doc.index.search(question, TOP_CHUNKS)
        if (picks.isEmpty()) {
            // Nothing matched (for example "what is this about?"): use the start of the document.
            picks = (0 until minOf(TOP_CHUNKS, doc.chunks.size)).toList()
        }
        val sb = StringBuilder()
        sb.append("Use the document excerpts below to answer the question. ")
            .append("If the answer is not in the excerpts, say you could not find it in the document.\n\n")
        picks.forEachIndexed { n, i ->
            sb.append("[Excerpt ").append(n + 1).append("]\n").append(doc.chunks[i]).append("\n\n")
        }
        sb.append("Question: ").append(question)
        return sb.toString()
    }

    // ---------------------------------------------------------------- chat

    fun newChat() {
        if (!generating) messages.clear()
    }

    fun stopGeneration() {
        cancelRequested = true
        NativeBridge.stop()
    }

    fun send(text: String) {
        val userText = text.trim()
        if (userText.isEmpty() || generating || status != ModelStatus.READY) return

        val doc = document
        messages.add(ChatMessage("user", userText))
        messages.add(ChatMessage("assistant", if (doc != null) "Reading the document..." else ""))
        generating = true
        val history = messages.toList().dropLast(1)

        viewModelScope.launch(Dispatchers.Default) {
            val reply = StringBuilder()

            val listener = object : TokenListener {
                override fun onToken(bytes: ByteArray) {
                    reply.append(String(bytes, Charsets.UTF_8))
                    messages[messages.lastIndex] = ChatMessage("assistant", reply.toString())
                }
            }

            val error = try {
                val lastText = if (doc != null) docPrompt(doc, userText) else userText
                val prompt = buildPrompt(history, lastText)
                NativeBridge.generate(
                    prompt.toByteArray(Charsets.UTF_8),
                    MAX_NEW_TOKENS,
                    TEMPERATURE,
                    listener
                )
            } catch (t: Throwable) {
                "Native error: ${t.message}"
            }

            val stats = formatStats(
                try {
                    NativeBridge.lastStats()
                } catch (t: Throwable) {
                    ""
                }
            )
            var finalText = reply.toString()
            if (error.isNotEmpty()) finalText += "\n\n[Error] $error"
            if (finalText.isBlank()) finalText = "(no reply)"
            messages[messages.lastIndex] = ChatMessage("assistant", finalText, stats)
            generating = false
        }
    }

    // ---------------------------------------------------------------- actions

    @Volatile
    private var cancelRequested = false

    private fun setAssistant(text: String) {
        messages[messages.lastIndex] = ChatMessage("assistant", text)
    }

    private fun setFinal(text: String, stats: String) {
        messages[messages.lastIndex] = ChatMessage("assistant", text, stats)
    }

    private fun formatDuration(seconds: Double): String {
        val s = seconds.toInt()
        return if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
    }

    private fun preview(text: String): String =
        if (text.length > 400) text.take(400) + "..." else text

    /** One self-contained model call without chat history. Returns (text, error). */
    private fun runOnce(
        instruction: String,
        body: String,
        maxTokens: Int,
        temperature: Float,
        onText: ((String) -> Unit)?
    ): Pair<String, String> {
        val sb = StringBuilder()
        val listener = object : TokenListener {
            override fun onToken(bytes: ByteArray) {
                sb.append(String(bytes, Charsets.UTF_8))
                onText?.invoke(sb.toString())
            }
        }
        val prompt = "<|im_start|>system\n" + SYSTEM_PROMPT + "<|im_end|>\n" +
            "<|im_start|>user\n" + instruction + "\n\n" + body + "<|im_end|>\n" +
            "<|im_start|>assistant\n"
        val error = try {
            NativeBridge.generate(prompt.toByteArray(Charsets.UTF_8), maxTokens, temperature, listener)
        } catch (t: Throwable) {
            "Native error: ${t.message}"
        }
        return Pair(sb.toString().trim(), error)
    }

    /** Evenly spaced picks (always including the first and last) when there are too many. */
    private fun pickEvenly(items: List<String>, max: Int): List<String> {
        if (items.size <= max) return items
        val picks = (0 until max)
            .map { (it * (items.size - 1).toDouble() / (max - 1)).roundToInt() }
            .distinct()
        return picks.map { items[it] }
    }

    /** Summarizes typed text, or the attached document when the box is empty. */
    fun summarize(inputText: String) {
        val typed = inputText.trim()
        val doc = document
        if (generating || status != ModelStatus.READY) return
        if (typed.isEmpty() && doc == null) return

        val sourceDoc = if (typed.isEmpty()) doc else null
        val source = sourceDoc?.text ?: typed
        val label = if (sourceDoc != null) "Summarize the attached document."
        else "Summarize this text:\n" + preview(typed)

        messages.add(ChatMessage("user", label))
        val cached = sourceDoc?.summary
        if (cached != null) {
            messages.add(ChatMessage("assistant", cached, "saved from earlier"))
            return
        }
        messages.add(ChatMessage("assistant", "Preparing..."))
        generating = true
        cancelRequested = false

        viewModelScope.launch(Dispatchers.Default) {
            val started = System.nanoTime()
            var error = ""
            var result = ""
            var note = ""
            try {
                val all = Retrieval.chunk(source, SECTION_WORDS, 0)
                val sections = pickEvenly(all, MAX_SECTIONS)
                if (sections.size < all.size) {
                    note = "\n\n(Long text: summarized ${sections.size} evenly spaced sections " +
                        "out of ${all.size}.)"
                }

                if (sections.size <= 1) {
                    setAssistant("Reading...")
                    val (text, err) = runOnce(
                        SUMMARY_ONE_INSTRUCTION, sections.firstOrNull() ?: source, 300, 0.3f
                    ) { setAssistant(it) }
                    result = text
                    error = err
                } else {
                    val partials = ArrayList<String>()
                    for ((n, section) in sections.withIndex()) {
                        if (cancelRequested) break
                        val elapsed = (System.nanoTime() - started) / 1e9
                        val eta = if (n > 0) {
                            val left = elapsed / n * (sections.size - n) + REDUCE_ESTIMATE_SECONDS
                            " (about ${formatDuration(left)} left)"
                        } else ""
                        setAssistant("Summarizing part ${n + 1} of ${sections.size}$eta")
                        val (text, err) = runOnce(SUMMARY_PART_INSTRUCTION, section, 100, 0.2f, null)
                        if (err.isNotEmpty()) {
                            error = err
                            break
                        }
                        if (cancelRequested) break
                        partials.add(text)
                    }
                    if (error.isEmpty() && !cancelRequested) {
                        setAssistant("Combining the parts...")
                        val body = partials.withIndex()
                            .joinToString("\n") { "Part ${it.index + 1}: ${it.value}" }
                        val (text, err) = runOnce(
                            SUMMARY_FINAL_INSTRUCTION, body, 350, 0.3f
                        ) { setAssistant(it) }
                        result = text
                        error = err
                    }
                }
            } catch (t: Throwable) {
                error = "Error: ${t.message}"
            }

            val took = formatDuration((System.nanoTime() - started) / 1e9)
            if (cancelRequested) {
                setFinal(if (result.isNotBlank()) result + note else "Stopped.", "stopped after $took")
            } else if (error.isNotEmpty()) {
                setFinal("[Error] $error", "")
            } else {
                if (sourceDoc != null && result.isNotBlank()) sourceDoc.summary = result
                setFinal(result.ifBlank { "(no summary)" } + note, "took $took")
            }
            generating = false
        }
    }

    /** Instant keywords for typed text, or the attached document when the box is empty. */
    fun keywords(inputText: String) {
        val typed = inputText.trim()
        val doc = document
        if (generating || (typed.isEmpty() && doc == null)) return

        val fromDoc = typed.isEmpty()
        val source = if (fromDoc) doc?.text ?: "" else typed
        messages.add(
            ChatMessage(
                "user",
                if (fromDoc) "Keywords for the attached document."
                else "Keywords for this text:\n" + preview(typed)
            )
        )
        messages.add(ChatMessage("assistant", "Finding keywords..."))
        generating = true

        viewModelScope.launch(Dispatchers.Default) {
            val list = try {
                Keywords.extract(source, 12)
            } catch (t: Throwable) {
                emptyList()
            }
            val reply = if (list.isEmpty()) "I could not find clear keywords in that text."
            else list.joinToString("\n") { "- $it" }
            setFinal(reply, "instant, no model used")
            generating = false
        }
    }

    /** Corrects grammar, spelling and punctuation of the typed text. */
    fun fixGrammar(inputText: String) {
        val typed = inputText.trim()
        if (typed.isEmpty() || generating || status != ModelStatus.READY) return

        messages.add(ChatMessage("user", "Fix the grammar:\n$typed"))
        messages.add(ChatMessage("assistant", ""))
        generating = true
        cancelRequested = false

        viewModelScope.launch(Dispatchers.Default) {
            val maxTokens = try {
                (NativeBridge.tokenCount(typed.toByteArray(Charsets.UTF_8)) * 3 / 2 + 32)
                    .coerceIn(64, 1024)
            } catch (t: Throwable) {
                512
            }
            val (text, error) = runOnce(GRAMMAR_INSTRUCTION, typed, maxTokens, 0f) { setAssistant(it) }
            val stats = formatStats(
                try {
                    NativeBridge.lastStats()
                } catch (t: Throwable) {
                    ""
                }
            )
            var finalText = text
            if (error.isNotEmpty()) finalText += "\n\n[Error] $error"
            if (finalText.isBlank()) finalText = "(no reply)"
            setFinal(finalText, stats)
            generating = false
        }
    }

    /** Turns "412;21300;38;15800" into "read 412 tokens in 21.3s | wrote 38 tokens at 2.4 tok/s". */
    private fun formatStats(raw: String): String {
        val p = raw.split(";")
        if (p.size != 4) return ""
        val nPrompt = p[0].toIntOrNull() ?: return ""
        val promptMs = p[1].toDoubleOrNull() ?: return ""
        val nGen = p[2].toIntOrNull() ?: return ""
        val genMs = p[3].toDoubleOrNull() ?: return ""

        val sb = StringBuilder()
        sb.append(String.format(Locale.US, "read %d tokens in %.1fs", nPrompt, promptMs / 1000.0))
        if (nGen > 0 && genMs > 0.0) {
            sb.append(String.format(Locale.US, " | wrote %d tokens at %.1f tok/s", nGen, nGen / (genMs / 1000.0)))
        }
        return sb.toString()
    }

    /** ChatML format (used by Qwen and many other models). Other formats come later. */
    private fun format(msgs: List<ChatMessage>, lastText: String): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(SYSTEM_PROMPT).append("<|im_end|>\n")
        for ((i, m) in msgs.withIndex()) {
            // Only the newest question carries the document excerpts.
            val t = if (i == msgs.lastIndex) lastText else m.text
            sb.append("<|im_start|>").append(m.role).append("\n")
                .append(t).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    /** Drops the oldest turns until the conversation fits the context window. */
    private fun buildPrompt(history: List<ChatMessage>, lastText: String): String {
        var start = 0
        while (true) {
            val prompt = format(history.subList(start, history.size), lastText)
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
        private const val TOP_CHUNKS = 3
        private const val SECTION_WORDS = 350
        private const val MAX_SECTIONS = 12
        private const val REDUCE_ESTIMATE_SECONDS = 90.0
        private val GENERAL_HINTS = listOf(
            "about", "summar", "overview", "main point", "main idea", "tl;dr", "tldr",
            "gist", "what is this", "what's this", "describe", "explain this"
        )
        private const val SUMMARY_PART_INSTRUCTION =
            "Summarize this part of a document in 2 or 3 short sentences. Focus on the key facts."
        private const val SUMMARY_FINAL_INSTRUCTION =
            "Below are short summaries of consecutive parts of one document. " +
                "Write a clear summary of the whole document: first one sentence saying what " +
                "the document is about, then 3 to 5 bullet points with the key points. " +
                "Do not mention the parts."
        private const val SUMMARY_ONE_INSTRUCTION =
            "Summarize the text below: first one sentence saying what it is about, " +
                "then 3 to 5 bullet points with the key points."
        private const val GRAMMAR_INSTRUCTION =
            "Correct the grammar, spelling and punctuation of the text below. " +
                "Keep the original meaning and tone. Reply with only the corrected text, nothing else."
        private const val TEMPERATURE = 0.7f
        private const val DEFAULT_MODEL_FILE = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
        private val DEFAULT_MODEL_URLS = listOf(
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"
        )
    }
}
