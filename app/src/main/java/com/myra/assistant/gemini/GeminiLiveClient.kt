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
 * Supports both:
 * - Gemini 2.5 Flash Live
 * - Gemini 3.1 Flash Live
 *
 * The existing 2.5 behavior is preserved.
 * Gemini 3.1 uses its newer realtime text/history behavior.
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

    /*
     * Rolling conversation history.
     *
     * Raw audio is never persisted.
     */
    private data class HistoryTurn(
        val role: String,
        val text: String
    )

    private val history = ArrayDeque<HistoryTurn>()

    private var pendingUserText = StringBuilder()
    private var pendingModelText = StringBuilder()

    private val historyLock = Any()

    /*
     * TRUE only after setupComplete.
     *
     * No realtime audio/text/toolResponse is sent before this.
     */
    private val sessionReady = AtomicBoolean(false)

    /*
     * Prevent overlapping connections.
     */
    private val connecting = AtomicBoolean(false)

    @Volatile
    private var lastOutgoingLabel: String = "none"


    /**
     * Gemini 3.1 model ID.
     */
    private fun isGemini31(): Boolean {
        return config?.model ==
                "gemini-3.1-flash-live-preview"
    }


    /**
     * Single outgoing-message choke point.
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
     * Start Live connection.
     *
     * Keep the original model-discovery behavior so 2.5 remains compatible.
     */
    fun connect(config: GeminiConfig) {

        this.config = config

        running.set(true)

        reconnectAttempts = 0

        scope.launch(Dispatchers.IO) {

            resolveWorkingModel(config)

            openSocket()
        }
    }


    /**
     * Resolve a Live-capable model for the API key.
     *
     * This is intentionally retained from the previously working version.
     */
    private fun resolveWorkingModel(
        cfg: GeminiConfig
    ) {

        if (cfg.apiKey.isBlank()) {
            return
        }

        modelCache[cfg.apiKey]?.let { cached ->

            if (cfg.model != cached) {

                config =
                    cfg.copy(
                        model = cached
                    )
            }

            return
        }

        try {

            val req =
                Request.Builder()
                    .url(
                        "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000&key=" +
                                cfg.apiKey
                    )
                    .build()

            http.newCall(req)
                .execute()
                .use { resp ->

                    val body =
                        resp.body?.string()
                            ?: return

                    val models =
                        JSONObject(body)
                            .optJSONArray("models")
                            ?: return

                    val bidi =
                        ArrayList<String>()

                    for (
                        i in 0 until models.length()
                    ) {

                        val model =
                            models.getJSONObject(i)

                        val methods =
                            model.optJSONArray(
                                "supportedGenerationMethods"
                            )
                            ?: continue

                        for (
                            j in 0 until methods.length()
                        ) {

                            if (
                                methods
                                    .getString(j)
                                    .equals(
                                        "bidiGenerateContent",
                                        true
                                    )
                            ) {

                                bidi.add(
                                    model
                                        .getString("name")
                                        .removePrefix(
                                            "models/"
                                        )
                                )
                            }
                        }
                    }

                    Logger.i(
                        TAG,
                        "Live-capable models for this key: $bidi"
                    )

                    if (bidi.isEmpty()) {

                        onEvent(
                            GeminiEvent.Error(
                                "This API key has no Live (bidiGenerateContent) models enabled."
                            )
                        )

                        return
                    }

                    /*
                     * Prefer exactly what the user selected.
                     */
                    val chosen =
                        if (
                            bidi.any {
                                it == cfg.model
                            }
                        ) {

                            cfg.model

                        } else {

                            bidi.first()
                        }

                    if (
                        chosen != cfg.model
                    ) {

                        config =
                            cfg.copy(
                                model = chosen
                            )

                        Logger.i(
                            TAG,
                            "Model ${cfg.model} unavailable; switching to $chosen"
                        )
                    }

                    modelCache[
                        cfg.apiKey
                    ] = chosen
                }

        } catch (e: Exception) {

            /*
             * If ListModels fails, retain the user's selected model.
             */
            Logger.e(
                TAG,
                "Model resolution failed",
                e
            )
        }
    }


    /**
     * Open WebSocket.
     */
    private fun openSocket() {

        val cfg =
            config ?: return

        if (cfg.apiKey.isBlank()) {

            onEvent(
                GeminiEvent.Error(
                    "Gemini API key is missing. Add it in Settings."
                )
            )

            return
        }

        if (
            !connecting.compareAndSet(
                false,
                true
            )
        ) {

            Logger.w(
                TAG,
                "openSocket ignored: a connection attempt is already in progress"
            )

            return
        }

        /*
         * New session = not ready until setupComplete.
         */
        sessionReady.set(false)

        /*
         * Discard stale socket.
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
     * WebSocket callbacks.
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

                /*
                 * Setup must be first application frame.
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
                                .append(
                                    r.code
                                )
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
     * Send Gemini Live setup.
     */
    private fun sendSetup(
        ws: WebSocket
    ) {

        val cfg =
            config ?: return

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

        /*
         * Base setup.
         */
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


        /*
         * Gemini 3.1:
         *
         * clientContent is only supported for initial history seeding.
         * Therefore explicitly enable initialHistoryInClientContent.
         */
        if (cfg.model ==
            "gemini-3.1-flash-live-preview"
        ) {

            setup.put(
                "historyConfig",
                JSONObject()
                    .put(
                        "initialHistoryInClientContent",
                        true
                    )
            )

            /*
             * 3.1 defaults to minimal thinking.
             *
             * Explicitly setting minimal keeps latency low and avoids
             * accidentally using an unsupported 2.5-style thinkingBudget.
             */
            setup.put(
                "generationConfig",
                generationConfig
                    .put(
                        "thinkingConfig",
                        JSONObject()
                            .put(
                                "thinkingLevel",
                                "minimal"
                            )
                    )
            )
        }


        /*
         * Automatic activity detection.
         *
         * Keep the previously working values.
         */
        setup.put(
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


        /*
         * Tools.
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

            lastOutgoingLabel =
                "setup"

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
     * Stream 16kHz mono PCM16 microphone audio.
     *
     * This format works for both 2.5 and 3.1.
     */
    fun sendAudio(
        pcm: ByteArray
    ) {

        /*
         * Never send before setupComplete.
         */
        if (!sessionReady.get()) {
            return
        }

        /*
         * PCM16 = 2 bytes/sample.
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

        /*
         * Current Live API format.
         *
         * DO NOT use mediaChunks.
         */
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
     * Send typed text.
     *
     * IMPORTANT:
     *
     * Gemini 2.5:
     *     clientContent
     *
     * Gemini 3.1:
     *     realtimeInput.text
     *
     * This is one of the main fixes.
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


        /*
         * Gemini 3.1 requires realtimeInput.text
         * for normal conversation updates.
         */
        if (isGemini31()) {

            val message =
                JSONObject()
                    .put(
                        "realtimeInput",
                        JSONObject()
                            .put(
                                "text",
                                text
                            )
                    )

            if (
                safeSend(
                    message.toString(),
                    "text"
                )
            ) {

                synchronized(historyLock) {

                    pendingUserText.append(
                        text
                    )
                }
            }

            return
        }


        /*
         * Original Gemini 2.5 behavior.
         */
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

        if (
            safeSend(
                message.toString(),
                "text"
            )
        ) {

            synchronized(historyLock) {

                pendingUserText.append(
                    text
                )
            }
        }
    }


    /**
     * Send function-call responses.
     *
     * Synchronous function calling is supported.
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

            val fr =
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

            /*
             * Empty function-call IDs are invalid.
             */
            if (r.id.isNotBlank()) {

                fr.put(
                    "id",
                    r.id
                )
            }

            arr.put(fr)
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
     * Handle incoming server messages.
     */
    private fun handleMessage(
        raw: String
    ) {

        try {

            val obj =
                JSONObject(raw)


            /*
             * Setup complete.
             */
            if (
                obj.has(
                    "setupComplete"
                )
            ) {

                sessionReady.set(true)

                /*
                 * For Gemini 3.1 the server has been told that
                 * initial history may arrive via clientContent.
                 */
                restoreConversationHistory()

                onEvent(
                    GeminiEvent.SetupComplete
                )

                return
            }


            /*
             * Function calls.
             */
            if (
                obj.has("toolCall")
            ) {

                val fcs =
                    obj
                        .getJSONObject(
                            "toolCall"
                        )
                        .optJSONArray(
                            "functionCalls"
                        )

                if (fcs != null) {

                    val calls =
                        ArrayList<GeminiFunctionCall>()

                    for (
                        i in 0 until fcs.length()
                    ) {

                        val c =
                            fcs.getJSONObject(i)

                        calls.add(
                            GeminiFunctionCall(
                                c.optString(
                                    "id"
                                ),
                                c.optString(
                                    "name"
                                ),
                                c.optJSONObject(
                                    "args"
                                )
                                    ?: JSONObject()
                            )
                        )
                    }

                    if (
                        calls.isNotEmpty()
                    ) {

                        onEvent(
                            GeminiEvent.ToolCall(
                                calls
                            )
                        )
                    }
                }

                return
            }


            /*
             * Server content.
             */
            if (
                obj.has(
                    "serverContent"
                )
            ) {

                val sc =
                    obj.getJSONObject(
                        "serverContent"
                    )


                /*
                 * User interrupted model.
                 */
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


                /*
                 * Input transcription.
                 */
                sc
                    .optJSONObject(
                        "inputTranscription"
                    )
                    ?.optString(
                        "text"
                    )
                    ?.takeIf {
                        it.isNotEmpty()
                    }
                    ?.let {

                        synchronized(
                            historyLock
                        ) {

                            /*
                             * Avoid double-appending typed text
                             * when transcription is also received.
                             */
                            if (
                                pendingUserText
                                    .toString()
                                    .isEmpty()
                            ) {

                                pendingUserText.append(
                                    it
                                )
                            }
                        }

                        onEvent(
                            GeminiEvent.InputTranscript(
                                it
                            )
                        )
                    }


                /*
                 * Output transcription.
                 */
                sc
                    .optJSONObject(
                        "outputTranscription"
                    )
                    ?.optString(
                        "text"
                    )
                    ?.takeIf {
                        it.isNotEmpty()
                    }
                    ?.let {

                        synchronized(
                            historyLock
                        ) {

                            pendingModelText.append(
                                it
                            )
                        }

                        onEvent(
                            GeminiEvent.OutputTranscript(
                                it
                            )
                        )
                    }


                /*
                 * IMPORTANT:
                 *
                 * Gemini 3.1 may put multiple parts in ONE
                 * serverContent event.
                 *
                 * Process ALL parts.
                 */
                sc
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


                            /*
                             * Audio response.
                             */
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

                                        try {

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

                                        } catch (e: Exception) {

                                            Logger.e(
                                                TAG,
                                                "Audio decode failed",
                                                e
                                            )
                                        }
                                    }
                                }
                        }
                    }


                /*
                 * Completed turn.
                 */
                if (
                    sc.optBoolean(
                        "turnComplete",
                        false
                    )
                ) {

                    commitPendingTurn()

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
     * Commit completed text turn.
     */
    private fun commitPendingTurn() {

        synchronized(
            historyLock
        ) {

            val user =
                pendingUserText
                    .toString()
                    .trim()

            val model =
                pendingModelText
                    .toString()
                    .trim()

            if (
                user.isNotEmpty()
            ) {

                history.addLast(
                    HistoryTurn(
                        "user",
                        user
                    )
                )
            }

            if (
                model.isNotEmpty()
            ) {

                history.addLast(
                    HistoryTurn(
                        "model",
                        model
                    )
                )
            }

            pendingUserText =
                StringBuilder()

            pendingModelText =
                StringBuilder()
        }
    }


    /**
     * Restore current-session history after reconnect.
     *
     * For Gemini 3.1 this is allowed because setup contains:
     *
     * historyConfig.initialHistoryInClientContent = true
     *
     * For Gemini 2.5 this remains the original behavior.
     */
    private fun restoreConversationHistory() {

        val turns =
            synchronized(
                historyLock
            ) {
                history.toList()
            }

        if (
            turns.isEmpty() ||
            !sessionReady.get()
        ) {
            return
        }

        val arr =
            JSONArray()

        turns.forEach { turn ->

            if (
                turn.text.isNotBlank()
            ) {

                arr.put(
                    JSONObject()
                        .put(
                            "role",
                            turn.role
                        )
                        .put(
                            "parts",
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put(
                                            "text",
                                            turn.text
                                        )
                                )
                        )
                )
            }
        }

        if (
            arr.length() == 0
        ) {
            return
        }

        val message =
            JSONObject()
                .put(
                    "clientContent",
                    JSONObject()
                        .put(
                            "turns",
                            arr
                        )
                        .put(
                            "turnComplete",
                            true
                        )
                )

        safeSend(
            message.toString(),
            "history"
        )

        Logger.i(
            TAG,
            "Restored complete current-session history: " +
                    "${turns.size} turns after reconnect"
        )
    }


    /**
     * Reconnect.
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

        scope.launch(
            Dispatchers.IO
        ) {

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

            if (
                running.get()
            ) {

                openSocket()
            }
        }
    }


    /**
     * Periodic session renewal.
     */
    private fun scheduleRenew() {

        renewJob?.cancel()

        renewJob =
            scope.launch {

                delay(
                    Constants.SESSION_RENEW_MS
                )

                if (
                    running.get()
                ) {

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
     * Close client.
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

        /*
         * Preserve original per-API-key model cache.
         */
        private val modelCache =
            java.util.concurrent.ConcurrentHashMap<
                    String,
                    String
                    >()
    }
}
