package com.doey.corex

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {
    private val default = Thread.getDefaultUncaughtExceptionHandler()
    private val file = File(context.getExternalFilesDir(null), "corex_crash.log")

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        try {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val text = buildString {
                appendLine("=== CRASH $time ===")
                appendLine("Thread: ${thread.name}")
                appendLine("Exception: ${ex.javaClass.name}")
                appendLine("Message: ${ex.message}")
                appendLine("Stack:")
                ex.stackTrace.take(20).forEach { appendLine("  at $it") }
                ex.cause?.let {
                    appendLine("Caused by: ${it.javaClass.name}: ${it.message}")
                    it.stackTrace.take(10).forEach { s -> appendLine("  at $s") }
                }
            }
            file.appendText(text)
        } catch (e: Exception) {}
        default?.uncaughtException(thread, ex)
    }

    companion object {
        fun install(context: Context) {
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context))
        }

        fun readLastCrash(context: Context): String {
            val file = File(context.getExternalFilesDir(null), "corex_crash.log")
            return if (file.exists()) file.readText().takeLast(2000) else "Sin crashes"
        }
    }
}
