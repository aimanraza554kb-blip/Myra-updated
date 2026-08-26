package com.myra.assistant.data.model

/**
 * Supported Gemini Live models.
 *
 * The older preview/live IDs used by the original project were retired by
 * Google. Keep current model IDs here so a previously saved old preference
 * automatically falls back to a currently supported Live model.
 */
enum class GeminiModel(
    val id: String,
    val displayName: String,
    val nativeAudio: Boolean
) {
    FLASH_3_1_LIVE(
        id = "gemini-3.1-flash-live-preview",
        displayName = "Gemini 3.1 Flash Live",
        nativeAudio = true
    ),
    FLASH_2_5_LIVE(
        id = "gemini-2.5-flash-native-audio-preview-12-2025",
        displayName = "Gemini 2.5 Flash Live",
        nativeAudio = true
    );

    companion object {
        fun fromId(id: String?): GeminiModel =
            entries.firstOrNull { it.id == id } ?: FLASH_3_1_LIVE
    }
}
