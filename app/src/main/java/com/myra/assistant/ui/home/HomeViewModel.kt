package com.myra.assistant.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.myra.assistant.data.ServiceLocator
import com.myra.assistant.data.model.ChatMessage
import com.myra.assistant.data.model.ConnectionState
import com.myra.assistant.service.MyraForegroundService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Drives the main voice screen. */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val session = ServiceLocator.voiceSessionManager

    val connectionState: StateFlow<ConnectionState> = session.connectionState
    val inputTranscript: StateFlow<String> = session.inputTranscript
    val outputTranscript: StateFlow<String> = session.outputTranscript
    val amplitude: StateFlow<Float> = session.amplitude
    val micMuted: StateFlow<Boolean> = session.micMuted
    val playbackMuted: StateFlow<Boolean> = session.playbackMuted
    val active: StateFlow<Boolean> = session.active
    val lastError: StateFlow<String> = session.lastError

    val messages: StateFlow<List<ChatMessage>> =
        ServiceLocator.conversationRepository.observeMessages()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleSession() {
        val context = getApplication<Application>()
        if (active.value) {
            MyraForegroundService.stop(context)
            session.stop()
        } else {
            MyraForegroundService.start(context)
        }
    }

    fun toggleMic() = session.toggleMic()
    fun togglePlayback() = session.togglePlayback()
    fun interrupt() = session.interrupt()
    fun sendText(text: String) = session.sendText(text)
}
