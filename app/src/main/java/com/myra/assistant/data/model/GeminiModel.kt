package com.myra.assistant.data.model

/**
 * Supported Gemini Live model.
 *
 * MYRA uses the same Live model that was working in the original build.
 * Do not automatically replace this with preview/native-audio model IDs.
 */
enum class GeminiModel(
    val id: String,
    val displayName: String
) {
    FLASH_LIVE_2_0(
        id = "gemini-2.0-flash-live-001",
        displayName = "Gemini 2.0 Flash Live"
    );

    companion object {
        fun fromId(id: String?): GeminiModel =
            // Also handles an old saved preference containing the removed
            // 2.5 native-audio preview model: always fall back to 2.0 Live.
            FLASH_LIVE_2_0
    }
}
