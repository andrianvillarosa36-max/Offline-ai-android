package com.offlineai.assistant

object NativeBridge {
    init {
        System.loadLibrary("offlineai")
    }

    external fun systemInfo(): String
}
