package com.myra.assistant.voice

import android.content.Context
import com.myra.assistant.audio.AudioPlayer
import com.myra.assistant.audio.AudioRecorder
import com.myra.assistant.audio.VoiceActivityDetector
import com.myra.assistant.data.model.ChatMessage
import com.myra.assistant.data.model.ConnectionState
import com.myra.assistant.data.repository.ConversationRepository
import com.myra.assistant.data.repository.MemoryRepository
import com.myra.assistant.data.repository.SettingsRepository
import com.myra.assistant.gemini.GeminiConfig
import com.myra.assistant.gemini.GeminiEvent
import com.myra.assistant.gemini.GeminiFunctionResponse
import com.myra.assistant.gemini.GeminiLiveClient
import com.myra.assistant.phone.PhoneController
import com.myra.assistant.phone.PhoneTools
import com.myra.assistant.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * The heart of MYRA. Wires the microphone, Gemini Live client, speaker and VAD
 * together and exposes reactive state for the UI. One shared instance lives in
 * the [com.myra.assistant.data.ServiceLocator] so the Activity, the floating
 * bubble and the foreground service all control the same conversation.
 */
class VoiceSessionManager(
    private val appContext: Context,
    private val settings: SettingsRepository,
    private val conversation: ConversationRepository,
    private val memory: MemoryRepository,
    private val phoneController: PhoneController
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _inputTranscript = MutableStateFlow("")
    val inputTranscript: StateFlow<String> = _inputTranscript.asStateFlow()

    private val _outputTranscript = MutableStateFlow("")
    val outputTranscript: StateFlow<String> = _outputTranscript.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _micMuted = MutableStateFlow(settings.micMuted())
    val micMuted: StateFlow<Boolean> = _micMuted.asStateFlow()

    private val _playbackMuted = MutableStateFlow(settings.playbackMuted())
    val playbackMuted: StateFlow<Boolean> = _playbackMuted.asStateFlow()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _lastError = MutableStateFlow("")

    /** Called after the Gemini session is explicitly stopped. */
    @Volatile
    var onSessionStopped: (() -> Unit)? = null
    val lastError: StateFlow<String> = _lastError.asStateFlow()

    private val vad = VoiceActivityDetector()
    private var client: GeminiLiveClient? = null
    private var recorder: AudioRecorder? = null
    private var player: AudioPlayer? = null

    private val inputBuffer = StringBuilder()
    private val outputBuffer = StringBuilder()

    fun start() {
        if (_active.value) {
            // The foreground service can survive an Activity/task restart. In that
            // case the manager may still say "active" while the old Live session
            // is no longer usable. Rebuild the session instead of silently doing
            // nothing, which is what caused MYRA to stop responding after reopen.
            val ready = client?.isSessionReady() == true
            val audioReady = recorder != null && player != null
            if (ready && audioReady) return

            Logger.w(TAG, "Active session is stale; restarting Gemini/audio resources")
            stop()
        }
        _active.value = true
        scope.launch {
            val personality = settings.personality()
            val memoryBlock = memory.contextBlock()
            val profile = buildString {
                append(settings.userProfile())
                if (memoryBlock.isNotBlank()) append("\nRemembered: \n").append(memoryBlock)
            }
            val systemPrompt = personality.systemPrompt(
                userName = settings.userName(),
                userProfile = profile,
                customAddon = settings.customPersonality()
            )
            // Read the key immediately before building the Live config. When the
            // foreground service wakes MYRA, Android may still be finishing the
            // settings write/process initialization. Retry briefly instead of
            // passing a transient blank key to Gemini.
            var apiKey = settings.apiKey().trim()
            var keyAttempts = 0
            while (apiKey.isBlank() && keyAttempts < 5) {
                delay(250L)
                apiKey = settings.apiKey().trim()
                keyAttempts++
            }
            if (apiKey.isBlank()) {
                _active.value = false
                _lastError.value = "Gemini API key is missing. Add it in Settings."
                _connectionState.value = ConnectionState.ERROR
                Logger.e(TAG, "Wake start: API key is blank after retrying")
                return@launch
            }

            val config = GeminiConfig(
                apiKey = apiKey,
                model = settings.model().id,
                voiceName = settings.voice().voiceName,
                systemInstruction = systemPrompt,
                language = settings.language(),
                toolsJson = PhoneTools.declarationsJson()
            )

            val audioPlayer = AudioPlayer().apply { muted = _playbackMuted.value; start() }
            player = audioPlayer

            val geminiClient = GeminiLiveClient(scope, ::onEvent)
            client = geminiClient

            // Load the whole address book once, up front, so name -> number
            // lookups during the session are instant and reliable.
            runCatching { phoneController.preloadContacts() }
                .onSuccess { Logger.i(TAG, "Preloaded $it contacts") }

            val micRecorder = AudioRecorder(
                onChunk = { pcm ->
                    _amplitude.value = vad.amplitude(pcm)
                    if (!_micMuted.value) geminiClient.sendAudio(pcm)
                },
                onError = { message ->
                    // Surface mic failures honestly instead of appearing to listen.
                    _lastError.value = message
                    _connectionState.value = ConnectionState.ERROR
                }
            ).apply { muted = _micMuted.value }
            recorder = micRecorder

            geminiClient.connect(config)
        }
    }

    /** Ensure the existing foreground-service session is actually usable. */
    fun ensureStarted() {
        start()
    }

    private fun onEvent(event: GeminiEvent) {
        when (event) {
            is GeminiEvent.Connected -> {
                _connectionState.value = ConnectionState.CONNECTED
                _lastError.value = ""
            }
            is GeminiEvent.SetupComplete -> {
                _connectionState.value = ConnectionState.LISTENING
                recorder?.start()
            }
            is GeminiEvent.AudioChunk -> {
                _connectionState.value = ConnectionState.SPEAKING
                player?.enqueue(event.pcm)
            }
            is GeminiEvent.Interrupted -> {
                player?.flush()
                _connectionState.value = ConnectionState.LISTENING
            }
            is GeminiEvent.InputTranscript -> {
                inputBuffer.append(event.text)
                _inputTranscript.value = inputBuffer.toString()
            }
            is GeminiEvent.OutputTranscript -> {
                outputBuffer.append(event.text)
                _outputTranscript.value = outputBuffer.toString()
            }
            is GeminiEvent.TurnComplete -> onTurnComplete()
            is GeminiEvent.StateChanged -> {
                if (event.state == ConnectionState.RECONNECTING) {
                    // Drop any half-transcribed turn and stop stale playback so a
                    // reconnect never yields duplicate text or overlapping speech.
                    inputBuffer.setLength(0)
                    outputBuffer.setLength(0)
                    _inputTranscript.value = ""
                    _outputTranscript.value = ""
                    player?.flush()
                }
                _connectionState.value = event.state
            }
            is GeminiEvent.ToolCall -> handleToolCall(event)
            is GeminiEvent.Error -> {
                _connectionState.value = ConnectionState.ERROR
                _lastError.value = event.message
                Logger.e(TAG, "Session error: ${event.message}")
            }
            is GeminiEvent.Closed -> _connectionState.value = ConnectionState.IDLE
        }
    }

    private fun onTurnComplete() {
        val userText = inputBuffer.toString().trim()
        val assistantText = outputBuffer.toString().trim()
        inputBuffer.setLength(0)
        outputBuffer.setLength(0)
        _inputTranscript.value = ""
        _outputTranscript.value = ""
        _connectionState.value = ConnectionState.LISTENING
        scope.launch {
            if (userText.isNotEmpty()) conversation.add(ChatMessage.Role.USER, userText)
            if (assistantText.isNotEmpty()) {
                conversation.add(ChatMessage.Role.ASSISTANT, assistantText)
            }
            if (settings.learningMode() && userText.isNotEmpty()) {
                phoneController.maybeLearn(userText)?.let { memory.remember(it) }
            }
        }
    }

    private fun handleToolCall(event: GeminiEvent.ToolCall) {
        scope.launch {
            val responses = event.calls.map { call ->
                val result = if (call.name == "remember") {
                    val fact = call.args.optString("fact")
                    if (fact.isNotBlank()) memory.remember(fact, pinned = true)
                    "Saved to memory"
                } else {
                    phoneController.dispatch(call.name, call.args)
                }
                GeminiFunctionResponse(call.id, call.name, result)
            }
            client?.sendToolResponse(responses)
        }
    }

    fun sendText(text: String) {
        client?.sendText(text)
        scope.launch { conversation.add(ChatMessage.Role.USER, text) }
    }

    fun toggleMic() {
        val muted = !_micMuted.value
        _micMuted.value = muted
        recorder?.muted = muted
        settings.setMicMuted(muted)
    }

    fun togglePlayback() {
        val muted = !_playbackMuted.value
        _playbackMuted.value = muted
        player?.muted = muted
        settings.setPlaybackMuted(muted)
    }

    /** Interrupt MYRA while she is speaking. */
    fun interrupt() = player?.flush()

    fun stop() {
        _active.value = false
        recorder?.stop(); recorder = null
        player?.stop(); player = null
        client?.close(); client = null
        inputBuffer.setLength(0)
        outputBuffer.setLength(0)
        _inputTranscript.value = ""
        _outputTranscript.value = ""
        _connectionState.value = ConnectionState.IDLE
        _amplitude.value = 0f
        onSessionStopped?.invoke()
    }

    companion object { private const val TAG = "VoiceSessionManager" }
}
