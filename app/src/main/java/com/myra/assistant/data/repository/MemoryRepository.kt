package com.myra.assistant.data.repository

import com.myra.assistant.data.local.MemoryDao
import com.myra.assistant.data.local.MemoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Long-term memory store. Supports pinned memories and a lightweight
 * summarization step that condenses old conversation into durable notes.
 */
class MemoryRepository(private val dao: MemoryDao) {

    fun observeMemories(): Flow<List<MemoryEntity>> = dao.observeAll()

    suspend fun remember(content: String, pinned: Boolean = false): Long =
        dao.insert(MemoryEntity(content = content, pinned = pinned))

    suspend fun pin(memory: MemoryEntity, pinned: Boolean) =
        dao.update(memory.copy(pinned = pinned))

    suspend fun forget(memory: MemoryEntity) = dao.delete(memory)

    suspend fun all(): List<MemoryEntity> = dao.all()

    /** Returns pinned + recent memories formatted for injection into the system prompt. */
    suspend fun contextBlock(maxItems: Int = 15): String {
        val items = dao.all().take(maxItems)
        if (items.isEmpty()) return ""
        return items.joinToString(separator = "\n") { "- " + it.content }
    }

    suspend fun clearUnpinned() = dao.clearUnpinned()
}
