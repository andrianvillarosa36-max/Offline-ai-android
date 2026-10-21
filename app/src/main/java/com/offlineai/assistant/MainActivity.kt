package com.offlineai.assistant

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

// Milestone 1 uses plain views to keep the first build simple.
// The real UI (Jetpack Compose) comes in milestone 2.
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            setPadding(pad, pad * 2, pad, pad)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }

        text.text = try {
            "Offline AI - milestone 1\n\nllama.cpp loaded OK.\n\n" + NativeBridge.systemInfo()
        } catch (t: Throwable) {
            "Failed to load the native library:\n$t"
        }

        setContentView(ScrollView(this).apply { addView(text) })
    }
}
