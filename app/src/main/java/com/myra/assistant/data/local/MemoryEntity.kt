package com.myra.assistant.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
