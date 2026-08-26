package com.myra.assistant.data.model

/** Prebuilt Gemini Live voices the user can choose in Settings. */
enum class VoiceOption(val voiceName: String) {
    PUCK("Puck"),
    CHARON("Charon"),
    KORE("Kore"),
    FENRIR("Fenrir"),
    AOEDE("Aoede"),
    LEDA("Leda"),
    ORUS("Orus"),
    ZEPHYR("Zephyr");

    companion object {
        fun fromName(name: String?): VoiceOption =
            entries.firstOrNull { it.voiceName == name } ?: AOEDE
    }
}
