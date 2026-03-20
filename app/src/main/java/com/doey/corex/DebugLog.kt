package com.doey.corex

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object DebugLog {
    private var logFile: File? = null
    private val entries = mutableListOf<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun init(context: Context) {
        logFile = File(context.filesDir, "corex_debug.log")
    }

    fun log(tag: String, msg: String) {
        val entry = "[${fmt.format(Date())}] $tag: $msg"
        entries.add(entry)
        if (entries.size > 200) entries.removeAt(0)
        try { logFile?.appendText(entry + "\n") } catch (e: Exception) {}
    }

    fun getLast(n: Int = 50): String = entries.takeLast(n).joinToString("\n")

    fun logError(tag: String, e: Exception) {
        log("ERR/$tag", "${e.javaClass.simpleName}: ${e.message}")
        try { logFile?.appendText("STACK: ${e.stackTraceToString().take(500)}\n") } catch (ex: Exception) {}
    }

    fun exportLog(context: Context): File {
        val export = File(context.getExternalFilesDir(null), "corex_debug_export.log")
        export.writeText(logFile?.readText() ?: "vacío")
        return export
    }
}
