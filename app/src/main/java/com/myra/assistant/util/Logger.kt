package com.myra.assistant.util

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Central logger. Mirrors log lines into an in-memory ring buffer so the
 * Developer options screen can display debug logs, and honours the user's
 * debug-log toggle.
 */
object Logger {

    private const val MAX_LINES = 500
    private val buffer = CopyOnWriteArrayList<String>()

    @Volatile
    var debugEnabled: Boolean = false

    fun d(tag: String, message: String) {
        if (debugEnabled) {
            Log.d(tag, message)
            append("D", tag, message)
        }
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        append("I", tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        append("W", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        append("E", tag, message + (throwable?.let { ": " + it.message } ?: ""))
    }

    private fun append(level: String, tag: String, message: String) {
        val line = "${System.currentTimeMillis()} $level/$tag: $message"
        buffer.add(line)
        while (buffer.size > MAX_LINES) {
            buffer.removeAt(0)
        }
    }

    fun snapshot(): List<String> = buffer.toList()

    fun clear() = buffer.clear()
}
