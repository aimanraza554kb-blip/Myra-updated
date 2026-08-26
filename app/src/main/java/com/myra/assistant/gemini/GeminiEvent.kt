package com.myra.assistant.gemini

import com.myra.assistant.data.model.ConnectionState
import org.json.JSONObject

/** Events emitted by [GeminiLiveClient] as the live session progresses. */
sealed interface GeminiEvent {
    data object Connected : GeminiEvent
    data object SetupComplete : GeminiEvent
    data class AudioChunk(val pcm: ByteArray) : GeminiEvent {
        override fun equals(other: Any?): Boolean =
            this === other || (other is AudioChunk && pcm.contentEquals(other.pcm))
        override fun hashCode(): Int = pcm.contentHashCode()
    }
    data class InputTranscript(val text: String) : GeminiEvent
    data class OutputTranscript(val text: String) : GeminiEvent
    data object TurnComplete : GeminiEvent
    data object Interrupted : GeminiEvent
    data class StateChanged(val state: ConnectionState) : GeminiEvent
    data class Error(val message: String) : GeminiEvent
    data class ToolCall(val calls: List<GeminiFunctionCall>) : GeminiEvent
    data object Closed : GeminiEvent
}

/** A single function call requested by the model during a live turn. */
data class GeminiFunctionCall(val id: String, val name: String, val args: JSONObject)

/** The app's result for a function call, sent back to the model. */
data class GeminiFunctionResponse(val id: String, val name: String, val result: String)

/** Immutable configuration for a Gemini Live session. */
data class GeminiConfig(
    val apiKey: String,
    val model: String,
    val voiceName: String,
    val systemInstruction: String,
    val language: String,
    val toolsJson: String? = null
)
