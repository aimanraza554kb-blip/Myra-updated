package com.myra.assistant.data.model

/** A single line of conversation shown in chat history and the live transcript. */
data class ChatMessage(
    val id: Long = 0,
    val role: Role,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class Role { USER, ASSISTANT }
}
