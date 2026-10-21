package com.offlineai.assistant

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                File(filesDir, CrashReport.FILE_NAME).writeText("Thread: ${thread.name}\n$sw")
            } catch (e: Throwable) {
                // nothing more we can do while crashing
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
