package com.myra.assistant.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresPermission
import com.myra.assistant.util.Logger
import java.util.Locale

/**
 * Lightweight always-ready wake-word listener. It runs only while Gemini is
 * disconnected, so it never competes with the Gemini microphone recorder.
 */
class MyraWakeWordListener(
    private val context: Context,
    private val languageTag: String,
    private val onWakeWord: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var restarting = false
    private var triggered = false

    private val wakePhrases = listOf(
        "hey myra",
        "hey mira",
        "hi myra",
        "hi mira",
        "myra"
    )

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (running || !SpeechRecognizer.isRecognitionAvailable(context)) return
        running = true
        triggered = false
        handler.post { createRecognizerAndListen() }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createRecognizerAndListen() {
        if (!running || triggered) return

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                languageTag.ifBlank { Locale.getDefault().toLanguageTag() }
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 350L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 200L)
        }

        try {
            recognizer?.startListening(intent)
        } catch (t: Throwable) {
            Logger.w(TAG, "Wake recognizer start failed: ${t.message}")
            scheduleRestart(150L)
        }
    }

    private fun check(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        for (raw in matches) {
            val normalized = raw.lowercase(Locale.getDefault())
                .replace(Regex("[^a-z0-9 ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            if (wakePhrases.any { normalized.contains(it) }) {
                trigger(raw)
                return
            }
        }
    }

    private fun trigger(text: String) {
        if (triggered) return
        triggered = true
        running = false
        Logger.i(TAG, "Detected wake phrase: $text")
        handler.post {
            try { recognizer?.cancel() } catch (_: Throwable) {}
            try { recognizer?.destroy() } catch (_: Throwable) {}
            recognizer = null
            onWakeWord()
        }
    }

    private fun scheduleRestart(delayMs: Long = 100L) {
        if (!running || triggered || restarting) return
        restarting = true
        handler.postDelayed({
            restarting = false
            if (running && !triggered) createRecognizerAndListen()
        }, delayMs)
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun restart() = scheduleRestart(100L)

    fun stop() {
        running = false
        restarting = false
        triggered = false
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel() } catch (_: Throwable) {}
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { scheduleRestart(50L) }
        override fun onError(error: Int) {
            Logger.d(TAG, "Wake recognizer error=$error")
            scheduleRestart(100L)
        }
        override fun onResults(results: Bundle?) {
            check(results)
            if (!triggered) scheduleRestart(50L)
        }
        override fun onPartialResults(partialResults: Bundle?) { check(partialResults) }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    companion object { private const val TAG = "MyraWakeWord" }
}
