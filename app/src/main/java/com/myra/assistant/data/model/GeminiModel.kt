package com.myra.assistant.data.model

/**
 * Supported Gemini Live models. The user can switch between them in Settings.
 *
 * Note: model ids are the values passed to the Live API (prefixed with
 * "models/" by the client). Update these strings if Google changes the
 * preview identifiers.
 */
enum class GeminiModel(
    val id: String,
    val displayName: String,
    val nativeAudio: Boolean
) {
    FLASH_NATIVE_AUDIO(
        id = "gemini-2.5-flash-preview-native-audio-dialog",
        displayName = "Gemini 2.5 Flash (Native Audio)",
        nativeAudio = true
    ),
    FLASH_LIVE_2_0(
        id = "gemini-2.0-flash-live-001",
        displayName = "Gemini 2.0 Flash Live",
        nativeAudio = false
    );

    companion object {
        fun fromId(id: String?): GeminiModel =
            entries.firstOrNull { it.id == id } ?: FLASH_LIVE_2_0
    }
}
