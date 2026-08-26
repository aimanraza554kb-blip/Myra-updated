package com.myra.assistant.data.model

/** High-level state of the Gemini Live session, surfaced to the UI. */
enum class ConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    LISTENING,
    SPEAKING,
    RECONNECTING,
    ERROR
}
