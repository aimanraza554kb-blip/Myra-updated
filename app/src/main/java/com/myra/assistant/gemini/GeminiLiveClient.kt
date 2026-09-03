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
 * WebSocket client for the Gemini Live API (BidiGenerateContent).
 *
 * Handles:
 * - Live WebSocket connection
 * - Setup handshake
 * - Streaming PCM microphone audio
 * - Streaming PCM audio responses
 * - Input/output transcription
 * - Function calling
 * - Automatic reconnect
 * - Session renewal
 * - Keepalive
 */
class GeminiLiveClient(
    private val scope: CoroutineScope,
    private val onEvent: (GeminiEvent) -> Unit
) {

    private val http = OkHttpClient.Builder()
        .pingInterval(
            Constants.HEARTBEAT_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var config: GeminiConfig? = null

    private val running = AtomicBoolean(false)

    private var reconnectAttempts = 0
    private var renewJob: Job? = null

    /**
     * TRUE only after Gemini sends setupComplete.
     *
     * Audio/text/tool responses must not be sent before setupComplete.
     */
    private val sessionReady = AtomicBoolean(false)

    /**
     * Prevents duplicate WebSocket connection attempts.
     */
    private val connecting = AtomicBoolean(false)

    /**
     * Used for useful diagnostics if Gemini closes the socket.
     */
    @Volatile
    private var lastOutgoingLabel: String = "none"


    /**
     * Safely sends a WebSocket message.
     */
    private fun safeSend(
        message: String,
        label: String
    ): Boolean {

        val ws = webSocket ?: run {
            Logger.w(
                TAG,
                "Drop '$label': no active socket"
            )
            return false
        }

        return try {

            lastOutgoingLabel = label

            val queued = ws.send(message)

            if (!queued) {
                Logger.w(
                    TAG,
                    "Drop '$label': send buffer full or socket closing"
                )
            } else if (label != "audio") {
                Logger.d(
                    TAG,
                    "-> $label (${message.length} bytes)"
                )
            }

            queued

        } catch (e: Exception) {

            Logger.e(
                TAG,
                "Send failed for '$label'",
                e
            )

            false
        }
    }


    /**
     * Starts Gemini Live connection.
     *
     * IMPORTANT:
     * We intentionally DO NOT call Google's ListModels endpoint here.
     *
     * The model selected in MYRA Settings is used directly.
     */
    fun connect(config: GeminiConfig) {

        this.config = config

        running.set(true)

        reconnectAttempts = 0

        scope.launch(Dispatchers.IO) {
            openSocket()
        }
    }


    /**
     * Opens a brand-new WebSocket session.
     */
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

        if (!connecting.compareAndSet(false, true)) {

            Logger.w(
                TAG,
                "openSocket ignored: connection already in progress"
            )

            return
        }

        /**
         * New session means audio must wait until setupComplete.
         */
        sessionReady.set(false)

        /**
         * Fully discard old socket.
         */
        webSocket?.let { old ->

            webSocket = null

            try {
                old.cancel()
            } catch (_: Exception) {
            }
        }

        val url =
            Constants.GEMINI_WS_HOST +
                    "?key=" +
                    cfg.apiKey

        val request =
            Request.Builder()
                .url(url)
                .build()

        onEvent(
            GeminiEvent.StateChanged(
                ConnectionState.CONNECTING
            )
        )

        webSocket =
            http.newWebSocket(
                request,
                listener
            )
    }


    /**
     * WebSocket listener.
     */
    private val listener =
        object : WebSocketListener() {

            override fun onOpen(
                ws: WebSocket,
                response: Response
            ) {

                connecting.set(false)

                Logger.i(
                    TAG,
                    "WebSocket open"
                )

                reconnectAttempts = 0

                /**
                 * Setup MUST be the first application frame.
                 */
                sendSetup(ws)

                scheduleRenew()

                onEvent(
                    GeminiEvent.Connected
                )
            }


            override fun onMessage(
                ws: WebSocket,
                text: String
            ) {

                handleMessage(text)
            }


            override fun onMessage(
                ws: WebSocket,
                bytes: ByteString
            ) {

                handleMessage(
                    bytes.utf8()
                )
            }


            override fun onClosing(
                ws: WebSocket,
                code: Int,
                reason: String
            ) {

                try {
                    ws.close(
                        NORMAL_CLOSURE,
                        null
                    )
                } catch (_: Exception) {
                }
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

                val renewing =
                    reason == "renew"

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

                if (running.get()) {

                    reconnect(
                        immediate = renewing
                    )

                } else {

                    onEvent(
                        GeminiEvent.Closed
                    )
                }
            }


            override fun onFailure(
                ws: WebSocket,
                t: Throwable,
                response: Response?
            ) {

                sessionReady.set(false)

                connecting.set(false)

                val detail =
                    buildString {

                        append(
                            t.message
                                ?: "Connection failed"
                        )

                        response?.let { r ->

                            append(
                                " (HTTP "
                            )
                                .append(r.code)
                                .append(")")

                            try {

                                r.body
                                    ?.string()
                                    ?.takeIf {
                                        it.isNotBlank()
                                    }
                                    ?.let {

                                        append(": ")
                                            .append(
                                                it.take(300)
                                            )
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


    /**
     * Sends the Gemini Live setup handshake.
     */
    private fun sendSetup(
        ws: WebSocket
    ) {

        val cfg = config ?: return

        val speechConfig =
            JSONObject()
                .put(
                    "voiceConfig",
                    JSONObject()
                        .put(
                            "prebuiltVoiceConfig",
                            JSONObject()
                                .put(
                                    "voiceName",
                                    cfg.voiceName
                                )
                        )
                )

        val generationConfig =
            JSONObject()
                .put(
                    "responseModalities",
                    JSONArray()
                        .put("AUDIO")
                )
                .put(
                    "speechConfig",
                    speechConfig
                )

        val setup =
            JSONObject()
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
                    JSONObject()
                        .put(
                            "parts",
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put(
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
                    JSONObject()
                        .put(
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

        /**
         * Add tools only when configured.
         */
        cfg.toolsJson
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {

                setup.put(
                    "tools",
                    JSONArray(it)
                )
            }

        val message =
            JSONObject()
                .put(
                    "setup",
                    setup
                )

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


    /**
     * Sends 16kHz mono PCM16 microphone audio.
     *
     * IMPORTANT:
     * Gemini Live expects realtimeInput.audio.
     *
     * We intentionally DO NOT use the old mediaChunks structure.
     */
    fun sendAudio(
        pcm: ByteArray
    ) {

        /**
         * Do not send audio before setupComplete.
         */
        if (!sessionReady.get()) {
            return
        }

        /**
         * PCM16 must contain complete 16-bit samples.
         */
        if (
            pcm.isEmpty() ||
            pcm.size % 2 != 0
        ) {
            return
        }

        val b64 =
            Base64.encodeToString(
                pcm,
                Base64.NO_WRAP
            )

        if (b64.isBlank()) {
            return
        }

        val audio =
            JSONObject()
                .put(
                    "mimeType",
                    "audio/pcm;rate=" +
                            Constants.INPUT_SAMPLE_RATE
                )
                .put(
                    "data",
                    b64
                )

        /**
         * CURRENT Live API format:
         *
         * realtimeInput:
         *   audio:
         *     mimeType
         *     data
         */
        val message =
            JSONObject()
                .put(
                    "realtimeInput",
                    JSONObject()
                        .put(
                            "audio",
                            audio
                        )
                )

        safeSend(
            message.toString(),
            "audio"
        )
    }


    /**
     * Sends typed text.
     */
    fun sendText(
        text: String
    ) {

        if (!sessionReady.get()) {

            Logger.w(
                TAG,
                "Drop text: session not ready"
            )

            return
        }

        if (text.isBlank()) {
            return
        }

        val turn =
            JSONObject()
                .put(
                    "role",
                    "user"
                )
                .put(
                    "parts",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put(
                                    "text",
                                    text
                                )
                        )
                )

        val message =
            JSONObject()
                .put(
                    "clientContent",
                    JSONObject()
                        .put(
                            "turns",
                            JSONArray()
                                .put(turn)
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


    /**
     * Sends function-call responses.
     */
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

        if (responses.isEmpty()) {
            return
        }

        val arr =
            JSONArray()

        responses.forEach { r ->

            val functionResponse =
                JSONObject()
                    .put(
                        "name",
                        r.name
                    )
                    .put(
                        "response",
                        JSONObject()
                            .put(
                                "result",
                                r.result
                            )
                    )

            /**
             * Empty function-call IDs are invalid,
             * therefore only add when available.
             */
            if (r.id.isNotBlank()) {

                functionResponse.put(
                    "id",
                    r.id
                )
            }

            arr.put(
                functionResponse
            )
        }

        val message =
            JSONObject()
                .put(
                    "toolResponse",
                    JSONObject()
                        .put(
                            "functionResponses",
                            arr
                        )
                )

        safeSend(
            message.toString(),
            "toolResponse"
        )
    }


    /**
     * Handles incoming Gemini Live messages.
     */
    private fun handleMessage(
        raw: String
    ) {

        try {

            val obj =
                JSONObject(raw)

            /**
             * Setup handshake completed.
             */
            if (obj.has("setupComplete")) {

                sessionReady.set(true)

                onEvent(
                    GeminiEvent.SetupComplete
                )

                return
            }


            /**
             * Function calling.
             */
            if (obj.has("toolCall")) {

                val functionCalls =
                    obj
                        .getJSONObject("toolCall")
                        .optJSONArray(
                            "functionCalls"
                        )

                if (functionCalls != null) {

                    val calls =
                        ArrayList<GeminiFunctionCall>()

                    for (
                        i in 0 until functionCalls.length()
                    ) {

                        val call =
                            functionCalls
                                .getJSONObject(i)

                        calls.add(
                            GeminiFunctionCall(
                                call.optString("id"),
                                call.optString("name"),
                                call.optJSONObject(
                                    "args"
                                ) ?: JSONObject()
                            )
                        )
                    }

                    if (calls.isNotEmpty()) {

                        onEvent(
                            GeminiEvent.ToolCall(
                                calls
                            )
                        )
                    }
                }

                return
            }


            /**
             * Server content.
             */
            if (obj.has("serverContent")) {

                val serverContent =
                    obj.getJSONObject(
                        "serverContent"
                    )


                /**
                 * User interruption.
                 */
                if (
                    serverContent.optBoolean(
                        "interrupted",
                        false
                    )
                ) {

                    onEvent(
                        GeminiEvent.Interrupted
                    )
                }


                /**
                 * Input transcription.
                 */
                serverContent
                    .optJSONObject(
                        "inputTranscription"
                    )
                    ?.optString("text")
                    ?.takeIf {
                        it.isNotEmpty()
                    }
                    ?.let {

                        onEvent(
                            GeminiEvent.InputTranscript(
                                it
                            )
                        )
                    }


                /**
                 * Output transcription.
                 */
                serverContent
                    .optJSONObject(
                        "outputTranscription"
                    )
                    ?.optString("text")
                    ?.takeIf {
                        it.isNotEmpty()
                    }
                    ?.let {

                        onEvent(
                            GeminiEvent.OutputTranscript(
                                it
                            )
                        )
                    }


                /**
                 * Model audio response.
                 */
                serverContent
                    .optJSONObject(
                        "modelTurn"
                    )
                    ?.optJSONArray(
                        "parts"
                    )
                    ?.let { parts ->

                        for (
                            i in 0 until parts.length()
                        ) {

                            val part =
                                parts.getJSONObject(i)

                            part
                                .optJSONObject(
                                    "inlineData"
                                )
                                ?.let { data ->

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

                                        if (
                                            pcm.isNotEmpty()
                                        ) {

                                            onEvent(
                                                GeminiEvent.AudioChunk(
                                                    pcm
                                                )
                                            )
                                        }
                                    }
                                }
                        }
                    }


                /**
                 * Turn completed.
                 */
                if (
                    serverContent.optBoolean(
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


    /**
     * Reconnect with exponential backoff.
     */
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
                        )
                        .coerceAtMost(
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


    /**
     * Renews the Live session periodically.
     */
    private fun scheduleRenew() {

        renewJob?.cancel()

        renewJob =
            scope.launch {

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


    /**
     * Fully closes the Live session.
     */
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
