package com.myra.assistant.data.repository

import com.myra.assistant.data.local.MessageDao
import com.myra.assistant.data.local.MessageEntity
import com.myra.assistant.data.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Persists and streams conversation history. */
class ConversationRepository(private val dao: MessageDao) {

    fun observeMessages(): Flow<List<ChatMessage>> =
        dao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun add(role: ChatMessage.Role, text: String): Long =
        dao.insert(MessageEntity(role = role.name, text = text, timestamp = System.currentTimeMillis()))

    suspend fun recentForContext(limit: Int = 20): List<ChatMessage> =
        dao.recent(limit).map { it.toModel() }.reversed()

    suspend fun clear() = dao.clear()

    private fun MessageEntity.toModel() = ChatMessage(
        id = id,
        role = if (role == ChatMessage.Role.USER.name) ChatMessage.Role.USER else ChatMessage.Role.ASSISTANT,
        text = text,
        timestamp = timestamp
    )
}
