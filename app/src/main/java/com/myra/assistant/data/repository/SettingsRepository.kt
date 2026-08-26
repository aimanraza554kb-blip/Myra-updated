package com.myra.assistant.data.repository

import com.myra.assistant.data.model.GeminiModel
import com.myra.assistant.data.model.Personality
import com.myra.assistant.data.model.VoiceOption
import com.myra.assistant.data.prefs.SecurePreferences
import com.myra.assistant.util.Constants
import org.json.JSONObject

/** Reads and writes all user settings through encrypted preferences. */
class SettingsRepository(private val prefs: SecurePreferences) {

    fun apiKey(): String = prefs.getString(Constants.KEY_API_KEY, "")
    fun setApiKey(value: String) = prefs.putString(Constants.KEY_API_KEY, value.trim())

    // Default to the native-audio model: it answers speech directly (audio->audio)
    // instead of transcribing to text first and then replying, which removes the
    // "detect words, then respond" delay. If a key doesn't have it enabled, the
    // client automatically falls back to another available Live model.
    fun model(): GeminiModel = GeminiModel.fromId(prefs.getString(Constants.KEY_MODEL, GeminiModel.FLASH_NATIVE_AUDIO.id))
    fun setModel(model: GeminiModel) = prefs.putString(Constants.KEY_MODEL, model.id)

    fun voice(): VoiceOption = VoiceOption.fromName(prefs.getString(Constants.KEY_VOICE, VoiceOption.AOEDE.voiceName))
    fun setVoice(voice: VoiceOption) = prefs.putString(Constants.KEY_VOICE, voice.voiceName)

    fun personality(): Personality = Personality.fromId(prefs.getString(Constants.KEY_PERSONALITY, Personality.ASSISTANT.id))
    fun setPersonality(p: Personality) = prefs.putString(Constants.KEY_PERSONALITY, p.id)

    fun language(): String = prefs.getString(Constants.KEY_LANGUAGE, "en-US")
    fun setLanguage(value: String) = prefs.putString(Constants.KEY_LANGUAGE, value)

    fun userName(): String = prefs.getString(Constants.KEY_USER_NAME, "")
    fun setUserName(value: String) = prefs.putString(Constants.KEY_USER_NAME, value)

    fun userProfile(): String = prefs.getString(Constants.KEY_USER_PROFILE, "")
    fun setUserProfile(value: String) = prefs.putString(Constants.KEY_USER_PROFILE, value)

    fun customPersonality(): String = prefs.getString("custom_personality", "")
    fun setCustomPersonality(value: String) = prefs.putString("custom_personality", value)

    fun handsFree(): Boolean = prefs.getBoolean(Constants.KEY_HANDS_FREE, false)
    fun setHandsFree(value: Boolean) = prefs.putBoolean(Constants.KEY_HANDS_FREE, value)

    fun wakeWordEnabled(): Boolean = prefs.getBoolean(Constants.KEY_WAKE_WORD, false)
    fun setWakeWordEnabled(value: Boolean) = prefs.putBoolean(Constants.KEY_WAKE_WORD, value)

    fun continuousListening(): Boolean = prefs.getBoolean(Constants.KEY_CONTINUOUS, false)
    fun setContinuousListening(value: Boolean) = prefs.putBoolean(Constants.KEY_CONTINUOUS, value)

    fun overlayEnabled(): Boolean = prefs.getBoolean(Constants.KEY_OVERLAY, false)
    fun setOverlayEnabled(value: Boolean) = prefs.putBoolean(Constants.KEY_OVERLAY, value)

    fun debugLogsEnabled(): Boolean = prefs.getBoolean(Constants.KEY_DEBUG_LOGS, false)
    fun setDebugLogsEnabled(value: Boolean) = prefs.putBoolean(Constants.KEY_DEBUG_LOGS, value)

    fun learningMode(): Boolean = prefs.getBoolean(Constants.KEY_LEARNING_MODE, true)
    fun setLearningMode(value: Boolean) = prefs.putBoolean(Constants.KEY_LEARNING_MODE, value)

    fun micMuted(): Boolean = prefs.getBoolean(Constants.KEY_MIC_MUTED, false)
    fun setMicMuted(value: Boolean) = prefs.putBoolean(Constants.KEY_MIC_MUTED, value)

    fun playbackMuted(): Boolean = prefs.getBoolean(Constants.KEY_PLAYBACK_MUTED, false)
    fun setPlaybackMuted(value: Boolean) = prefs.putBoolean(Constants.KEY_PLAYBACK_MUTED, value)

    fun primeContacts(): String = prefs.getString(Constants.KEY_PRIME_CONTACTS, "")
    fun setPrimeContacts(value: String) = prefs.putString(Constants.KEY_PRIME_CONTACTS, value)

    /** Export all settings except the API key as JSON. */
    fun exportJson(includeApiKey: Boolean): String {
        val json = JSONObject()
        prefs.all().forEach { (k, v) ->
            if (!includeApiKey && k == Constants.KEY_API_KEY) return@forEach
            json.put(k, v)
        }
        return json.toString(2)
    }

    /** Import settings from a JSON string previously produced by [exportJson]. */
    fun importJson(raw: String) {
        val json = JSONObject(raw)
        json.keys().forEach { key ->
            when (val value = json.get(key)) {
                is Boolean -> prefs.putBoolean(key, value)
                is Int -> prefs.putInt(key, value)
                else -> prefs.putString(key, value.toString())
            }
        }
    }
}
