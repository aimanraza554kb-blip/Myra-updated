package com.myra.assistant.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.myra.assistant.data.ServiceLocator
import com.myra.assistant.data.model.GeminiModel
import com.myra.assistant.service.MyraForegroundService
import com.myra.assistant.data.model.Personality
import com.myra.assistant.data.model.VoiceOption
import com.myra.assistant.util.Logger

/** Backing logic for the Settings screen. */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = ServiceLocator.settingsRepository

    fun apiKey() = settings.apiKey()
    fun setApiKey(value: String) {
        settings.setApiKey(value)
        ServiceLocator.voiceSessionManager.restart()
    }

    fun model() = settings.model()
    fun setModel(model: GeminiModel) {
        settings.setModel(model)
        ServiceLocator.voiceSessionManager.restart()
    }

    fun voice() = settings.voice()
    fun setVoice(voice: VoiceOption) {
        settings.setVoice(voice)
        ServiceLocator.voiceSessionManager.restart()
    }

    fun personality() = settings.personality()
    fun setPersonality(p: Personality) {
        settings.setPersonality(p)
        ServiceLocator.voiceSessionManager.restart()
    }

    fun language() = settings.language()
    fun setLanguage(value: String) {
        settings.setLanguage(value)
        ServiceLocator.voiceSessionManager.restart()
    }

    fun userName() = settings.userName()
    fun setUserName(value: String) = settings.setUserName(value)

    fun userProfile() = settings.userProfile()
    fun setUserProfile(value: String) = settings.setUserProfile(value)

    fun customPersonality() = settings.customPersonality()
    fun setCustomPersonality(value: String) {
        settings.setCustomPersonality(value)
        ServiceLocator.voiceSessionManager.restart()
    }

    fun primeContacts() = settings.primeContacts()
    fun setPrimeContacts(value: String) = settings.setPrimeContacts(value)

    fun handsFree() = settings.handsFree()
    fun setHandsFree(value: Boolean) = settings.setHandsFree(value)

    fun wakeWord() = settings.wakeWordEnabled()
    fun setWakeWord(value: Boolean) {
        settings.setWakeWordEnabled(value)
        val context = getApplication<Application>()
        if (value) {
            // Start the foreground service in wake-only mode. Gemini stays idle
            // until the wake phrase is detected or the user presses Start.
            MyraForegroundService.start(context)
        } else if (!ServiceLocator.voiceSessionManager.active.value) {
            // Stop the wake-only service when Gemini is not manually running.
            MyraForegroundService.stop(context)
        }
    }

    fun continuous() = settings.continuousListening()
    fun setContinuous(value: Boolean) = settings.setContinuousListening(value)

    fun overlay() = settings.overlayEnabled()
    fun setOverlay(value: Boolean) = settings.setOverlayEnabled(value)

    fun learningMode() = settings.learningMode()
    fun setLearningMode(value: Boolean) = settings.setLearningMode(value)

    fun debugLogs() = settings.debugLogsEnabled()
    fun setDebugLogs(value: Boolean) {
        settings.setDebugLogsEnabled(value)
        Logger.debugEnabled = value
    }

    fun debugLogSnapshot(): List<String> = Logger.snapshot()

    fun exportSettings(): String = settings.exportJson(includeApiKey = false)
    fun importSettings(raw: String) = settings.importJson(raw)
}
