package com.myra.assistant.util

/** Global constants shared across MYRA. */
object Constants {

    const val SECURE_PREFS_FILE = "myra_secure_prefs"
    const val DATABASE_NAME = "myra.db"

    // Gemini Live endpoint (BidiGenerateContent websocket).
    const val GEMINI_WS_HOST =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    // Audio format used by the Gemini Live API.
    const val INPUT_SAMPLE_RATE = 16000   // microphone -> Gemini
    const val OUTPUT_SAMPLE_RATE = 24000  // Gemini -> speaker

    // WebSocket keepalive / reconnect timings (milliseconds).
    const val HEARTBEAT_INTERVAL_MS = 20_000L
    const val RECONNECT_BASE_DELAY_MS = 1_000L
    const val RECONNECT_MAX_DELAY_MS = 30_000L
    const val SESSION_RENEW_MS = 9 * 60 * 1000L // renew before the 10-min preview limit

    // Notification channels.
    const val CHANNEL_FOREGROUND = "myra_foreground"
    const val CHANNEL_BUBBLE = "myra_bubble"
    const val NOTIFICATION_ID_FOREGROUND = 1001
    const val NOTIFICATION_ID_BUBBLE = 1002

    // Preference keys.
    const val KEY_API_KEY = "gemini_api_key"
    const val KEY_MODEL = "gemini_model"
    const val KEY_VOICE = "gemini_voice"
    const val KEY_PERSONALITY = "personality"
    const val KEY_LANGUAGE = "language"
    const val KEY_HANDS_FREE = "hands_free"
    const val KEY_WAKE_WORD = "wake_word_enabled"
    const val KEY_CONTINUOUS = "continuous_listening"
    const val KEY_OVERLAY = "overlay_enabled"
    const val KEY_DEBUG_LOGS = "debug_logs"
    const val KEY_LEARNING_MODE = "learning_mode"
    const val KEY_USER_NAME = "user_name"
    const val KEY_USER_PROFILE = "user_profile"
    const val KEY_PRIME_CONTACTS = "prime_contacts"
    const val KEY_MIC_MUTED = "mic_muted"
    const val KEY_PLAYBACK_MUTED = "playback_muted"
}
