package com.myra.assistant.data

import android.content.Context
import com.myra.assistant.data.local.AppDatabase
import com.myra.assistant.data.prefs.SecurePreferences
import com.myra.assistant.data.repository.ConversationRepository
import com.myra.assistant.data.repository.MemoryRepository
import com.myra.assistant.data.repository.SettingsRepository
import com.myra.assistant.phone.PhoneController
import com.myra.assistant.voice.VoiceSessionManager

/**
 * Manual dependency injection container. Keeps a single instance of each
 * repository and the shared [VoiceSessionManager] so the UI and services all
 * talk to the same live session.
 */
object ServiceLocator {

    private lateinit var appContext: Context

    val securePreferences: SecurePreferences by lazy { SecurePreferences(appContext) }
    val database: AppDatabase by lazy { AppDatabase.build(appContext) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(securePreferences) }
    val conversationRepository: ConversationRepository by lazy { ConversationRepository(database.messageDao()) }
    val memoryRepository: MemoryRepository by lazy { MemoryRepository(database.memoryDao()) }
    val phoneController: PhoneController by lazy { PhoneController(appContext, settingsRepository) }

    val voiceSessionManager: VoiceSessionManager by lazy {
        VoiceSessionManager(
            appContext = appContext,
            settings = settingsRepository,
            conversation = conversationRepository,
            memory = memoryRepository,
            phoneController = phoneController
        )
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
