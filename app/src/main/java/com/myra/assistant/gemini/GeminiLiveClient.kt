package com.myra.assistant.gemini

import android.util.Base64
import com.myra.assistant.data.model.ConnectionState
import com.myra.assistant.util.Constants
import com.myra.assistant.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket client for the Gemini Live API (BidiGenerateContent). Handles setup,
 * streaming PCM audio in/out, input/output transcription, interruptions,
 * automatic reconnect with backoff, keepalive pings and periodic session renewal.
 *
 * The model selected in Settings is used directly.
 * No automatic model discovery or model switching is performed.
 */
class GeminiLiveClient(
    private val scope: CoroutineScope,
    private val onEvent: (GeminiEvent) -> Unit
) {

    private val http = OkHttpClient.Builder()
        .pingInterval(Constants.HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var config: GeminiConfig? = null
    private val running = AtomicBoolean(false)
    private var reconnectAttempts = 0
    private var renewJob: Job? = null

    // True ONLY between receiving setupComplete and the socket closing.
    // Every realtimeInput / clientContent / toolResponse must wait for this.
    private val sessionReady = AtomicBoolean(false)

    // Prevents overlapping (re)connect attempts from opening duplicate sockets.
    private val connecting = AtomicBoolean(false)

    // Remembers the last frame type sent, so a 1007 close names the culprit.
    @Volatile
    private var lastOutgoingLabel: String = "none"

    /**
     * Single choke-point for every outgoing frame: refuses to send on a dead or
     * not-yet-ready socket, never throws, and logs what was sent.
     */
    private fun safeSend(message: String, label: String): Boolean {
        val ws = webSocket ?: run {
            Logger.w(TAG, "Drop '$label': no active socket")
            return false
        }

        return try {
            lastOutgoingLabel = label
            val queued = ws.send(message)

            if (!queued) {
                Logger.w(TAG, "Drop '$label': send buffer full or socket closing")
            } else if (label != "audio") {
                Logger.d(TAG, "-> $label (${message.length} bytes)")
            }

            queued
        } catch (e: Exception) {
            Logger.e(TAG, "Send failed for '$label'", e)
            false
        }
    }

    fun connect(config: GeminiConfig) {
        this.config = config
        running.set(true)
        reconnectAttempts = 0

        // IMPORTANT:
        // Use the model selected in Settings directly.
        // Do NOT query ListModels and do NOT replace the selected model.
        scope.launch(Dispatchers.IO) {
            openSocket()
        }
    }

    private fun openSocket() {
        val cfg = config ?: return

        if (cfg.apiKey.isBlank()) {
            onEvent(
                GeminiEvent.Error(
                    "Gemini API key is missing. Add it in Settings."
                )
            )
            return
        }

        // Only one connection attempt at a time.
        if (!connecting.compareAndSet(false, true)) {
            Logger.w(
                TAG,
                "openSocket ignored: a connection attempt is already in progress"
            )
            return
        }

        // Always start a BRAND-NEW session.
        sessionReady.set(false)

        webSocket?.let { old ->
            webSocket = null
            try {
                old.cancel()
            } catch (_: Exception) {
            }
        }

        val url = Constants.GEMINI_WS_HOST + "?key=" + cfg.apiKey
        val request = Request.Builder()
            .url(url)
            .build()

        Logger.i(TAG, "Opening Gemini Live socket with model: ${cfg.model}")

        onEvent(
            GeminiEvent.StateChanged(
                ConnectionState.CONNECTING
            )
        )

        webSocket = http.newWebSocket(
            request,
            listener
        )
    }

    private val listener = object : WebSocketListener() {

        override fun onOpen(
            ws: WebSocket,
            response: Response
        ) {
            connecting.set(false)

            Logger.i(TAG, "WebSocket open")

            reconnectAttempts = 0

            // Setup MUST be the first frame.
            // sessionReady stays false until setupComplete.
            sendSetup(ws)

            scheduleRenew()

            onEvent(GeminiEvent.Connected)
        }

        override fun onMessage(
            ws: WebSocket,
            text: String
        ) = handleMessage(text)

        override fun onMessage(
            ws: WebSocket,
            bytes: ByteString
        ) = handleMessage(bytes.utf8())

        override fun onClosing(
            ws: WebSocket,
            code: Int,
            reason: String
        ) {
            ws.close(
                NORMAL_CLOSURE,
                null
            )
        }

        override fun onClosed(
            ws: WebSocket,
            code: Int,
            reason: String
        ) {
            sessionReady.set(false)
            connecting.set(false)

            Logger.i(
                TAG,
                "WebSocket closed: $code '$reason' " +
                    "(last frame sent: $lastOutgoingLabel)"
            )

            val renewing = reason == "renew"

            if (
                code != NORMAL_CLOSURE &&
                reason.isNotBlank() &&
                !renewing
            ) {
                onEvent(
                    GeminiEvent.Error(
                        "Server closed ($code): $reason"
                    )
                )
            }

            // Planned renewal reconnects immediately.
            // Genuine drops use exponential backoff.
            if (running.get()) {
                reconnect(immediate = renewing)
            } else {
                onEvent(GeminiEvent.Closed)
            }
        }

        override fun onFailure(
            ws: WebSocket,
            t: Throwable,
            response: Response?
        ) {
            sessionReady.set(false)
            connecting.set(false)

            val detail = buildString {
                append(
                    t.message ?: "Connection failed"
                )

                response?.let { r ->
                    append(" (HTTP ")
                        .append(r.code)
                        .append(")")

                    try {
                        r.body
                            ?.string()
                            ?.takeIf { it.isNotBlank() }
                            ?.let {
                                append(": ")
                                    .append(it.take(300))
                            }
                    } catch (_: Exception) {
                    }
                }
            }

            Logger.e(
                TAG,
                "WebSocket failure: $detail",
                t
            )

            onEvent(
                GeminiEvent.Error(detail)
            )

            if (running.get()) {
                reconnect()
            }
        }
    }

    private fun sendSetup(ws: WebSocket) {
        val cfg = config ?: return

        val speechConfig = JSONObject().put(
            "voiceConfig",
            JSONObject().put(
                "prebuiltVoiceConfig",
                JSONObject().put(
                    "voiceName",
                    cfg.voiceName
                )
            )
        )

        val generationConfig = JSONObject()
            .put(
                "responseModalities",
                JSONArray().put("AUDIO")
            )
            .put(
                "speechConfig",
                speechConfig
            )

        val setup = JSONObject()
            // IMPORTANT:
            // This is the exact model selected in Settings.
            // There is NO fallback to another model.
            .put(
                "model",
                "models/" + cfg.model
            )
            .put(
                "generationConfig",
                generationConfig
            )
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put(
                            "text",
                            cfg.systemInstruction
                        )
                    )
                )
            )
            .put(
                "inputAudioTranscription",
                JSONObject()
            )
            .put(
                "outputAudioTranscription",
                JSONObject()
            )
            .put(
                "realtimeInputConfig",
                JSONObject().put(
                    "automaticActivityDetection",
                    JSONObject()
                        .put(
                            "disabled",
                            false
                        )
                        .put(
                            "prefixPaddingMs",
                            10
                        )
                        .put(
                            "silenceDurationMs",
                            40
                        )
                )
            )

        cfg.toolsJson
            ?.takeIf { it.isNotBlank() }
            ?.let {
                setup.put(
                    "tools",
                    JSONArray(it)
                )
            }

        val message = JSONObject()
            .put(
                "setup",
                setup
            )

        // Setup is the handshake and therefore bypasses sessionReady.
        try {
            lastOutgoingLabel = "setup"

            ws.send(
                message.toString()
            )

            Logger.d(
                TAG,
                "-> setup for model ${cfg.model}"
            )
        } catch (e: Exception) {
            Logger.e(
                TAG,
                "Setup send failed",
                e
            )
        }
    }

    /** Stream a chunk of 16kHz mono PCM16 microphone audio to Gemini. */
    fun sendAudio(pcm: ByteArray) {
        // Never stream before setupComplete.
        if (!sessionReady.get()) return

        // PCM16 = 2 bytes/sample.
        if (pcm.isEmpty() || pcm.size % 2 != 0) return

        val b64 = Base64.encodeToString(
            pcm,
            Base64.NO_WRAP
        )

        if (b64.isBlank()) return

        val chunk = JSONObject()
            .put(
                "mimeType",
                "audio/pcm;rate=" +
                    Constants.INPUT_SAMPLE_RATE
            )
            .put(
                "data",
                b64
            )

        val message = JSONObject().put(
            "realtimeInput",
            JSONObject().put(
                "mediaChunks",
                JSONArray().put(chunk)
            )
        )

        safeSend(
            message.toString(),
            "audio"
        )
    }

    /** Send a typed text turn. */
    fun sendText(text: String) {
        if (!sessionReady.get()) {
            Logger.w(
                TAG,
                "Drop text: session not ready"
            )
            return
        }

        if (text.isBlank()) return

        val turn = JSONObject()
            .put(
                "role",
                "user"
            )
            .put(
                "parts",
                JSONArray().put(
                    JSONObject().put(
                        "text",
                        text
                    )
                )
            )

        val message = JSONObject().put(
            "clientContent",
            JSONObject()
                .put(
                    "turns",
                    JSONArray().put(turn)
                )
                .put(
                    "turnComplete",
                    true
                )
        )

        safeSend(
            message.toString(),
            "text"
        )
    }

    /** Send function-call results back to the model. */
    fun sendToolResponse(
        responses: List<GeminiFunctionResponse>
    ) {
        if (!sessionReady.get()) {
            Logger.w(
                TAG,
                "Drop toolResponse: session not ready"
            )
            return
        }

        if (responses.isEmpty()) return

        val arr = JSONArray()

        responses.forEach { r ->
            val fr = JSONObject()
                .put(
                    "name",
                    r.name
                )
                .put(
                    "response",
                    JSONObject().put(
                        "result",
                        r.result
                    )
                )

            // Only include id when one actually exists.
            if (r.id.isNotBlank()) {
                fr.put(
                    "id",
                    r.id
                )
            }

            arr.put(fr)
        }

        val message = JSONObject().put(
            "toolResponse",
            JSONObject().put(
                "functionResponses",
                arr
            )
        )

        safeSend(
            message.toString(),
            "toolResponse"
        )
    }

    private fun handleMessage(raw: String) {
        try {
            val obj = JSONObject(raw)

            if (obj.has("setupComplete")) {
                // Handshake complete.
                sessionReady.set(true)

                onEvent(
                    GeminiEvent.SetupComplete
                )

                return
            }

            if (obj.has("toolCall")) {
                val fcs = obj
                    .getJSONObject("toolCall")
                    .optJSONArray("functionCalls")

                if (fcs != null) {
                    val calls =
                        ArrayList<GeminiFunctionCall>()

                    for (i in 0 until fcs.length()) {
                        val c =
                            fcs.getJSONObject(i)

                        calls.add(
                            GeminiFunctionCall(
                                c.optString("id"),
                                c.optString("name"),
                                c.optJSONObject("args")
                                    ?: JSONObject()
                            )
                        )
                    }

                    if (calls.isNotEmpty()) {
                        onEvent(
                            GeminiEvent.ToolCall(calls)
                        )
                    }
                }

                return
            }

            if (obj.has("serverContent")) {
                val sc =
                    obj.getJSONObject("serverContent")

                if (
                    sc.optBoolean(
                        "interrupted",
                        false
                    )
                ) {
                    onEvent(
                        GeminiEvent.Interrupted
                    )
                }

                sc.optJSONObject(
                    "inputTranscription"
                )
                    ?.optString("text")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let {
                        onEvent(
                            GeminiEvent.InputTranscript(it)
                        )
                    }

                sc.optJSONObject(
                    "outputTranscription"
                )
                    ?.optString("text")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let {
                        onEvent(
                            GeminiEvent.OutputTranscript(it)
                        )
                    }

                sc.optJSONObject(
                    "modelTurn"
                )
                    ?.optJSONArray("parts")
                    ?.let { parts ->

                        for (i in 0 until parts.length()) {
                            val part =
                                parts.getJSONObject(i)

                            part.optJSONObject(
                                "inlineData"
                            )?.let { data ->

                                val mime =
                                    data.optString(
                                        "mimeType",
                                        ""
                                    )

                                if (
                                    mime.startsWith(
                                        "audio"
                                    )
                                ) {
                                    val pcm =
                                        Base64.decode(
                                            data.getString(
                                                "data"
                                            ),
                                            Base64.NO_WRAP
                                        )

                                    onEvent(
                                        GeminiEvent.AudioChunk(
                                            pcm
                                        )
                                    )
                                }
                            }

                            // Do NOT emit modelTurn text here.
                            // With AUDIO response modality the spoken
                            // response arrives through outputTranscription.
                        }
                    }

                if (
                    sc.optBoolean(
                        "turnComplete",
                        false
                    )
                ) {
                    onEvent(
                        GeminiEvent.TurnComplete
                    )
                }
            }

        } catch (e: Exception) {
            Logger.e(
                TAG,
                "Failed to parse message",
                e
            )
        }
    }

    private fun reconnect(
        immediate: Boolean = false
    ) {
        onEvent(
            GeminiEvent.StateChanged(
                ConnectionState.RECONNECTING
            )
        )

        renewJob?.cancel()

        scope.launch(Dispatchers.IO) {

            val delayMs =
                if (immediate) {
                    0L
                } else {
                    (
                        Constants.RECONNECT_BASE_DELAY_MS *
                            (
                                1L shl
                                    reconnectAttempts
                                        .coerceAtMost(5)
                            )
                    ).coerceAtMost(
                        Constants.RECONNECT_MAX_DELAY_MS
                    )
                }

            if (!immediate) {
                reconnectAttempts++
            }

            Logger.i(
                TAG,
                "Reconnecting in ${delayMs}ms " +
                    "(attempt $reconnectAttempts)"
            )

            delay(delayMs)

            if (running.get()) {
                openSocket()
            }
        }
    }

    private fun scheduleRenew() {
        renewJob?.cancel()

        renewJob = scope.launch {
            delay(
                Constants.SESSION_RENEW_MS
            )

            if (running.get()) {
                Logger.i(
                    TAG,
                    "Renewing session"
                )

                webSocket?.close(
                    NORMAL_CLOSURE,
                    "renew"
                )
            }
        }
    }

    fun close() {
        running.set(false)
        sessionReady.set(false)
        connecting.set(false)

        renewJob?.cancel()

        webSocket?.let {
            try {
                it.close(
                    NORMAL_CLOSURE,
                    "client closed"
                )
            } catch (_: Exception) {
            }
        }

        webSocket = null

        onEvent(
            GeminiEvent.StateChanged(
                ConnectionState.IDLE
            )
        )
    }

    companion object {
        private const val TAG =
            "GeminiLiveClient"

        private const val NORMAL_CLOSURE =
            1000
    }
}
