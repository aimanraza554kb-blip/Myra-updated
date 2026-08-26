package com.myra.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.myra.assistant.data.ServiceLocator
import com.myra.assistant.util.Constants
import com.myra.assistant.util.Logger

/**
 * Application entry point. Initializes the [ServiceLocator] (manual dependency
 * injection), notification channels and the debug-log flag.
 */
class MyraApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        ServiceLocator.init(this)
        Logger.debugEnabled = ServiceLocator.settingsRepository.debugLogsEnabled()
        createNotificationChannels()
        Logger.i("MyraApplication", "MYRA initialized")
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val foreground = NotificationChannel(
            Constants.CHANNEL_FOREGROUND,
            getString(R.string.fgs_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.fgs_channel_desc) }

        val bubble = NotificationChannel(
            Constants.CHANNEL_BUBBLE,
            "MYRA Bubble",
            NotificationManager.IMPORTANCE_MIN
        )
        manager.createNotificationChannel(foreground)
        manager.createNotificationChannel(bubble)
    }

    companion object {
        @Volatile
        lateinit var instance: MyraApplication
            private set
    }
}
