package com.offlineai.assistant

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * Shows what went wrong the next time the app opens, so crashes can be
 * debugged on a phone without a computer. Uses plain views on purpose, so it
 * still works if the main (Compose) UI is what crashed.
 */
object CrashReport {
    const val FILE_NAME = "last_crash.txt"

    private val BAD_REASONS = setOf(
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE
    )

    private fun reasonName(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH (Java exception)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE (native crash)"
        ApplicationExitInfo.REASON_ANR -> "ANR (app not responding)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (killed by the system)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        else -> "OTHER ($r)"
    }

    /** Returns a report if the app crashed since it was last opened, otherwise null. */
    fun pending(activity: Activity): String? {
        val sb = StringBuilder()

        val file = File(activity.filesDir, FILE_NAME)
        if (file.exists()) {
            try {
                sb.append("Java crash:\n").append(file.readText()).append("\n")
            } catch (e: Exception) {
                sb.append("Java crash file could not be read.\n")
            }
        }

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val prefs = activity.getSharedPreferences("offlineai", Context.MODE_PRIVATE)
                val seen = prefs.getLong("seen_exit_ts", 0L)
                var newest = seen
                val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                for (e in am.getHistoricalProcessExitReasons(activity.packageName, 0, 5)) {
                    if (e.timestamp <= seen) continue
                    if (e.timestamp > newest) newest = e.timestamp
                    if (e.reason in BAD_REASONS) {
                        sb.append("Process exit: ").append(reasonName(e.reason))
                            .append("\n  description: ").append(e.description)
                            .append("\n  status: ").append(e.status).append("\n")
                    }
                }
                prefs.edit().putLong("seen_exit_ts", newest).apply()
            } catch (e: Exception) {
                // exit history is only a bonus
            }
        }

        if (sb.isBlank()) return null

        val version = try {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        val header = "App ${version}, ${Build.MANUFACTURER} ${Build.MODEL}, " +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n"
        return header + sb.toString()
    }

    fun show(activity: Activity, report: String, onRetry: () -> Unit) {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(activity).apply {
            text = "Offline AI closed unexpectedly last time. " +
                "Tap Copy report, then paste it to Claude."
            textSize = 16f
        })

        root.addView(Button(activity).apply {
            text = "Copy report"
            setOnClickListener {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Offline AI crash report", report))
                text = "Copied"
            }
        })

        root.addView(Button(activity).apply {
            text = "Open the app anyway"
            setOnClickListener {
                File(activity.filesDir, FILE_NAME).delete()
                onRetry()
            }
        })

        val body = TextView(activity).apply {
            text = report
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(
            ScrollView(activity).apply { addView(body) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        activity.setContentView(root)
    }
}
