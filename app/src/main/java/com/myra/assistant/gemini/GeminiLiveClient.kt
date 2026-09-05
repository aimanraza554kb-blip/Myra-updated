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

    // Keep a small rolling conversation history locally so a planned Live-session
    // renewal/reconnect does not make MYRA forget the current conversation.
    // Only completed text turns are stored; raw audio is never persisted.
    private data class HistoryTurn(val role: String, val text: String)
    private val history = ArrayDeque<HistoryTurn>()
    private var pendingUserText = StringBuilder()
    private var pendingModelText = StringBuilder()
    private val historyLock = Any()

    // True ONLY between receiving setupComplete and the socket closing. Every
    // realtimeInput / clientContent / toolResponse must wait for this. Sending
    // anything before the setup handshake finishes makes the Live API close the
    // socket with code 1007 ("invalid argument"). Because the mic streams
    // continuously across the 9-minute session renewal and across reconnects,
    // this gate is what actually prevents the mid-session 1007 disconnect loop.
    private val sessionReady = AtomicBoolean(false)
    // Prevents overlapping (re)connect attempts from opening duplicate sockets.
    private val connecting = AtomicBoolean(false)
    // Remembers the last frame type sent, so a 1007 close names the culprit.
    @Volatile private var lastOutgoingLabel: String = "none"

    /**
     * Single choke-point for every outgoing frame: refuses to send on a dead or
     * not-yet-ready socket, never throws, and logs what was sent (audio frames
     * are counted rather than dumped to avoid flooding the log).
     */
    private fun safeSend(message: String, label: String): Boolean {
        val ws = webSocket ?: run {
            Logger.w(TAG, "Drop '$label': no active socket")
            return false
        }
        return try {
            lastOutgoingLabel = label
            val queued = ws.send(message)
            if (!queued) Logger.w(TAG, "Drop '$label': send buffer full or socket closing")
            else if (label != "audio") Logger.d(TAG, "-> $label (${message.length} bytes)")
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
        // Model discovery + socket open both do blocking network I/O, so keep
        // them off the Default (CPU) dispatcher to avoid stalling other work.
        scope.launch(Dispatchers.IO) {
            resolveWorkingModel(config)
            openSocket()
        }
    }

    /**
     * Query the REST ListModels endpoint so we connect with a model this API key
     * actually supports for bidiGenerateContent (the Live API). Different keys and
     * projects expose different Live models, so we auto-pick a working one instead
     * of hard-coding a name that may be unavailable for the user.
     */
    private fun resolveWorkingModel(cfg: GeminiConfig) {
        if (cfg.apiKey.isBlank()) return
        // Skip the ListModels round-trip if we already resolved a good model for
        // this key earlier in the process - makes reconnects/renewals faster.
        modelCache[cfg.apiKey]?.let { cached ->
            if (cfg.model != cached) config = cfg.copy(model = cached)
            return
        }
        try {
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000&key=" + cfg.apiKey)
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return
                val models = JSONObject(body).optJSONArray("models") ?: return
                val bidi = ArrayList<String>()
                for (i in 0 until models.length()) {
                    val m = models.getJSONObject(i)
                    val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
                    for (j in 0 until methods.length()) {
                        if (methods.getString(j).equals("bidiGenerateContent", true)) {
                            bidi.add(m.getString("name").removePrefix("models/"))
                        }
                    }
                }
                Logger.i(TAG, "Live-capable models for this key: $bidi")
                if (bidi.isEmpty()) {
                    onEvent(GeminiEvent.Error("This API key has no Live (bidiGenerateContent) models enabled."))
                    return
                }
                val chosen = if (bidi.any { it == cfg.model }) cfg.model else bidi.first()
                if (chosen != cfg.model) {
                    config = cfg.copy(model = chosen)
                    Logger.i(TAG, "Model ${cfg.model} unavailable; switching to $chosen")
                }
                modelCache[cfg.apiKey] = chosen
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Model resolution failed", e)
        }
    }

    private fun openSocket() {
        val cfg = config ?: return
        if (cfg.apiKey.isBlank()) {
            onEvent(GeminiEvent.Error("Gemini API key is missing. Add it in Settings."))
            return
        }
        // Only one connection attempt at a time; the guard is released in
        // onOpen/onFailure/onClosed.
        if (!connecting.compareAndSet(false, true)) {
            Logger.w(TAG, "openSocket ignored: a connection attempt is already in progress")
            return
        }
        // Always start a BRAND-NEW session: block sends until the new handshake
        // completes and fully discard the previous socket so we never reuse a
        // half-dead session or leak a stale reference.
        sessionReady.set(false)
        webSocket?.let { old ->
            webSocket = null
            try { old.cancel() } catch (_: Exception) {}
        }
        val url = Constants.GEMINI_WS_HOST + "?key=" + cfg.apiKey
        val request = Request.Builder().url(url).build()
        onEvent(GeminiEvent.StateChanged(ConnectionState.CONNECTING))
        webSocket = http.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            connecting.set(false)
            Logger.i(TAG, "WebSocket open")
            reconnectAttempts = 0
            // Setup MUST be the first frame; sessionReady stays false until the
            // server acknowledges it with setupComplete.
            sendSetup(ws)
            scheduleRenew()
            onEvent(GeminiEvent.Connected)
        }

        override fun onMessage(ws: WebSocket, text: String) = handleMessage(text)

        override fun onMessage(ws: WebSocket, bytes: ByteString) = handleMessage(bytes.utf8())

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            ws.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            sessionReady.set(false)
            connecting.set(false)
            Logger.i(TAG, "WebSocket closed: $code '$reason' (last frame sent: $lastOutgoingLabel)")
            val renewing = reason == "renew"
            if (code != NORMAL_CLOSURE && reason.isNotBlank() && !renewing) {
                onEvent(GeminiEvent.Error("Server closed ($code): $reason"))
            }
            // A planned session renewal should reconnect instantly and silently;
            // only genuine drops use exponential backoff.
            if (running.get()) reconnect(immediate = renewing) else onEvent(GeminiEvent.Closed)
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            sessionReady.set(false)
            connecting.set(false)
            val detail = buildString {
                append(t.message ?: "Connection failed")
                response?.let { r ->
                    append(" (HTTP ").append(r.code).append(")")
                    try {
                        r.body?.string()?.takeIf { it.isNotBlank() }?.let { append(": ").append(it.take(300)) }
                    } catch (_: Exception) {
                    }
                }
            }
            Logger.e(TAG, "WebSocket failure: $detail", t)
            onEvent(GeminiEvent.Error(detail))
            if (running.get()) reconnect()
        }
    }

    private fun sendSetup(ws: WebSocket) {
        val cfg = config ?: return
        val speechConfig = JSONObject().put(
            "voiceConfig",
            JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", cfg.voiceName))
        )
        val generationConfig = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", speechConfig)

        val setup = JSONObject()
            .put("model", "models/" + cfg.model)
            .put("generationConfig", generationConfig)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", cfg.systemInstruction))))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())

        // Gemini 3.1 only accepts clientContent for initial history seeding.
        // Enable that explicitly because reconnect/session renewal restores the
        // current MYRA conversation immediately after setupComplete.
        if (cfg.model == "gemini-3.1-flash-live-preview") {
            setup.put(
                "historyConfig",
                JSONObject().put("initialHistoryInClientContent", true)
            )
            setup.put(
                "generationConfig",
                generationConfig.put(
                    "thinkingConfig",
                    JSONObject().put("thinkingLevel", "minimal")
                )
            )
        }
            // Low-latency turn-taking: as soon as the user stops speaking we want a
            // reply almost immediately. Only the numeric VAD fields are set here.
            // NOTE: do NOT add startOfSpeechSensitivity/endOfSpeechSensitivity enums
            // - those were rejected by the Live API with close code 1007 ("invalid
            // argument"). The disabled/prefixPaddingMs/silenceDurationMs shape below
            // matches Google's documented working setup.
        setup.put(
            "realtimeInputConfig",
            JSONObject().put(
                "automaticActivityDetection",
                JSONObject()
                    .put("disabled", false)
                    .put("prefixPaddingMs", 10)
                    // Practically the floor: commit end-of-turn after ~2 silent
                    // 20ms frames so MYRA starts replying almost instantly. Going
                    // lower risks cutting the user off during natural pauses.
                    .put("silenceDurationMs", 20)
            )
        )

        cfg.toolsJson?.takeIf { it.isNotBlank() }?.let { setup.put("tools", JSONArray(it)) }

        val message = JSONObject().put("setup", setup)
        // Sent directly on the freshly opened socket (this IS the handshake, so it
        // bypasses the sessionReady gate). Wrapped so a send failure can't crash.
        try {
            lastOutgoingLabel = "setup"
            ws.send(message.toString())
            Logger.d(TAG, "-> setup for model ${cfg.model}")
        } catch (e: Exception) {
            Logger.e(TAG, "Setup send failed", e)
        }
    }

    /** Stream a chunk of 16kHz mono PCM16 microphone audio to Gemini. */
    fun sendAudio(pcm: ByteArray) {
        // Root-cause guard: never stream audio until the current session's setup
        // handshake is acknowledged. This is what stops the mic from hitting a
        // freshly-reconnected/renewed socket before setupComplete (the 1007).
        if (!sessionReady.get()) return
        // Never forward empty or misaligned frames (PCM16 = 2 bytes/sample).
        if (pcm.isEmpty() || pcm.size % 2 != 0) return
        val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        if (b64.isBlank()) return
        // Current Gemini Live WebSocket schema uses realtimeInput.audio directly.
        // The older realtimeInput.mediaChunks field is deprecated and now causes
        // server close 1007 (invalid argument).
        val audio = JSONObject()
            .put("mimeType", "audio/pcm;rate=" + Constants.INPUT_SAMPLE_RATE)
            .put("data", b64)
        val message = JSONObject().put(
            "realtimeInput",
            JSONObject().put("audio", audio)
        )
        safeSend(message.toString(), "audio")
    }

    /** Send a typed text turn (used by the chat input box). */
    fun sendText(text: String) {
        if (!sessionReady.get()) { Logger.w(TAG, "Drop text: session not ready"); return }
        if (text.isBlank()) return

        // Gemini 3.1 uses realtimeInput.text for normal conversation turns.
        // clientContent is reserved for initial history seeding on 3.1.
        if (config?.model == "gemini-3.1-flash-live-preview") {
            val message = JSONObject().put(
                "realtimeInput",
                JSONObject().put("text", text)
            )
            if (safeSend(message.toString(), "text")) {
                synchronized(historyLock) {
                    pendingUserText.append(text)
                }
            }
            return
        }

        // Preserve the original 2.5 Live behavior.
        val turn = JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", text)))
        val message = JSONObject().put(
            "clientContent",
            JSONObject().put("turns", JSONArray().put(turn)).put("turnComplete", true)
        )
        if (safeSend(message.toString(), "text")) {
            synchronized(historyLock) {
                pendingUserText.append(text)
            }
        }
    }

    /** Send function-call results back to the model so it can finish the turn. */
    fun sendToolResponse(responses: List<GeminiFunctionResponse>) {
        if (!sessionReady.get()) { Logger.w(TAG, "Drop toolResponse: session not ready"); return }
        if (responses.isEmpty()) return
        val arr = JSONArray()
        responses.forEach { r ->
            val fr = JSONObject()
                .put("name", r.name)
                .put("response", JSONObject().put("result", r.result))
            // Only include id when the matching function call actually had one;
            // an empty id is an invalid argument.
            if (r.id.isNotBlank()) fr.put("id", r.id)
            arr.put(fr)
        }
        val message = JSONObject().put("toolResponse", JSONObject().put("functionResponses", arr))
        safeSend(message.toString(), "toolResponse")
    }

    private fun handleMessage(raw: String) {
        try {
            val obj = JSONObject(raw)
            if (obj.has("setupComplete")) {
                // Handshake done: it is now safe to stream audio and tool results.
                sessionReady.set(true)
                restoreConversationHistory()
                onEvent(GeminiEvent.SetupComplete)
                return
            }
            if (obj.has("toolCall")) {
                val fcs = obj.getJSONObject("toolCall").optJSONArray("functionCalls")
                if (fcs != null) {
                    val calls = ArrayList<GeminiFunctionCall>()
                    for (i in 0 until fcs.length()) {
                        val c = fcs.getJSONObject(i)
                        calls.add(
                            GeminiFunctionCall(
                                c.optString("id"),
                                c.optString("name"),
                                c.optJSONObject("args") ?: JSONObject()
                            )
                        )
                    }
                    if (calls.isNotEmpty()) onEvent(GeminiEvent.ToolCall(calls))
                }
                return
            }
            if (obj.has("serverContent")) {
                val sc = obj.getJSONObject("serverContent")
                if (sc.optBoolean("interrupted", false)) onEvent(GeminiEvent.Interrupted)
                sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }
                    ?.let {
                        synchronized(historyLock) { pendingUserText.append(it) }
                        onEvent(GeminiEvent.InputTranscript(it))
                    }
                sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }
                    ?.let {
                        synchronized(historyLock) { pendingModelText.append(it) }
                        onEvent(GeminiEvent.OutputTranscript(it))
                    }
                sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        part.optJSONObject("inlineData")?.let { data ->
                            val mime = data.optString("mimeType", "")
                            if (mime.startsWith("audio")) {
                                val pcm = Base64.decode(data.getString("data"), Base64.NO_WRAP)
                                onEvent(GeminiEvent.AudioChunk(pcm))
                            }
                        }
                        // Intentionally NOT emitting modelTurn text parts here. With an
                        // AUDIO response modality the spoken reply already streams in via
                        // outputTranscription; emitting the text too would duplicate MYRA's
                        // transcript on screen.
                    }
                }
                if (sc.optBoolean("turnComplete", false)) {
                    commitPendingTurn()
                    onEvent(GeminiEvent.TurnComplete)
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to parse message", e)
        }
    }

    /** Save every completed turn for the lifetime of this MYRA session. */
    private fun commitPendingTurn() {
        synchronized(historyLock) {
            val user = pendingUserText.toString().trim()
            val model = pendingModelText.toString().trim()
            if (user.isNotEmpty()) history.addLast(HistoryTurn("user", user))
            if (model.isNotEmpty()) history.addLast(HistoryTurn("model", model))
            pendingUserText = StringBuilder()
            pendingModelText = StringBuilder()
        }
    }

    /** Rehydrate the new Live session with the complete current-session conversation. */
    private fun restoreConversationHistory() {
        val turns = synchronized(historyLock) { history.toList() }
        if (turns.isEmpty() || !sessionReady.get()) return

        val arr = JSONArray()
        turns.forEach { turn ->
            if (turn.text.isNotBlank()) {
                arr.put(
                    JSONObject()
                        .put("role", turn.role)
                        .put("parts", JSONArray().put(JSONObject().put("text", turn.text)))
                )
            }
        }
        if (arr.length() == 0) return

        val message = JSONObject().put(
            "clientContent",
            JSONObject().put("turns", arr).put("turnComplete", true)
        )
        safeSend(message.toString(), "history")
        Logger.i(TAG, "Restored complete current-session history: ${turns.size} turns after reconnect")
    }

    private fun reconnect(immediate: Boolean = false) {
        onEvent(GeminiEvent.StateChanged(ConnectionState.RECONNECTING))
        renewJob?.cancel()
        scope.launch(Dispatchers.IO) {
            val delayMs = if (immediate) 0L else
                (Constants.RECONNECT_BASE_DELAY_MS * (1L shl reconnectAttempts.coerceAtMost(5)))
                    .coerceAtMost(Constants.RECONNECT_MAX_DELAY_MS)
            if (!immediate) reconnectAttempts++
            Logger.i(TAG, "Reconnecting in ${delayMs}ms (attempt $reconnectAttempts)")
            delay(delayMs)
            if (running.get()) openSocket()
        }
    }

    private fun scheduleRenew() {
        renewJob?.cancel()
        renewJob = scope.launch {
            delay(Constants.SESSION_RENEW_MS)
            if (running.get()) {
                Logger.i(TAG, "Renewing session")
                webSocket?.close(NORMAL_CLOSURE, "renew")
            }
        }
    }

    fun close() {
        running.set(false)
        sessionReady.set(false)
        connecting.set(false)
        renewJob?.cancel()
        webSocket?.let { try { it.close(NORMAL_CLOSURE, "client closed") } catch (_: Exception) {} }
        webSocket = null
        onEvent(GeminiEvent.StateChanged(ConnectionState.IDLE))
    }

    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val NORMAL_CLOSURE = 1000
        // Per-API-key cache of a known Live-capable model, shared across sessions
        // in this process so we only run model discovery once.
        private val modelCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    }
}
