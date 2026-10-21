package com.offlineai.assistant

/** Receives generated text as raw UTF-8 bytes (decoded on the Kotlin side). */
interface TokenListener {
    fun onToken(bytes: ByteArray)
}

object NativeBridge {
    init {
        System.loadLibrary("offlineai")
    }

    external fun systemInfo(): String

    /** Returns "" on success, otherwise an error message. */
    external fun loadModel(path: String, nCtx: Int, nThreads: Int): String

    external fun unloadModel()

    external fun tokenCount(text: ByteArray): Int

    /** Returns "" on success (or when stopped), otherwise an error message. */
    external fun generate(
        prompt: ByteArray,
        maxNewTokens: Int,
        temperature: Float,
        listener: TokenListener
    ): String

    external fun stop()
}
