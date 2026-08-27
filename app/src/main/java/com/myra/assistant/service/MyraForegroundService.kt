package com.myra.assistant.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.myra.assistant.MainActivity
import com.myra.assistant.R
import com.myra.assistant.data.ServiceLocator
import com.myra.assistant.util.Constants
import com.myra.assistant.util.Logger
import com.myra.assistant.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Foreground service that keeps wake-word listening alive independently of Gemini. */
class MyraForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeWordListener: MyraWakeWordListener? = null
    private var stateWatcherStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        if (!PermissionHelper.hasPermission(this, Manifest.permission.RECORD_AUDIO)) {
            Logger.w(TAG, "Microphone permission missing; stopping foreground service")
            stopSelf()
            return START_NOT_STICKY
        }

        if (ServiceLocator.settingsRepository.wakeWordEnabled()) {
            watchSessionState()
        } else {
            ServiceLocator.voiceSessionManager.start()
        }

        Logger.i(TAG, "Foreground service started")
        return START_STICKY
    }

    private fun watchSessionState() {
        if (stateWatcherStarted) return
        stateWatcherStarted = true
        serviceScope.launch {
            ServiceLocator.voiceSessionManager.active.collectLatest { active ->
                if (active) stopWakeWordMode() else startWakeWordMode()
            }
        }
    }

    private fun startWakeWordMode() {
        if (!ServiceLocator.settingsRepository.wakeWordEnabled()) return
        if (ServiceLocator.voiceSessionManager.active.value) return
        if (wakeWordListener != null) return

        wakeWordListener = MyraWakeWordListener(
            context = this,
            languageTag = ServiceLocator.settingsRepository.language(),
            onWakeWord = {
                stopWakeWordMode()
                Logger.i(TAG, "Wake word detected; starting existing MYRA session")
                // IMPORTANT: use the exact same VoiceSessionManager.start() path
                // as the working app. No new GeminiConfig or API-key handling here.
                ServiceLocator.voiceSessionManager.start()
            }
        )

        try {
            wakeWordListener?.start()
        } catch (t: Throwable) {
            Logger.e(TAG, "Unable to start wake listener", t)
            wakeWordListener = null
        }
    }

    private fun stopWakeWordMode() {
        wakeWordListener?.stop()
        wakeWordListener = null
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, Constants.NOTIFICATION_ID_FOREGROUND, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(Constants.NOTIFICATION_ID_FOREGROUND, notification)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, Constants.CHANNEL_FOREGROUND)
            .setContentTitle(getString(R.string.fgs_notification_title))
            .setContentText(getString(R.string.fgs_notification_text))
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    override fun onDestroy() {
        stopWakeWordMode()
        serviceScope.coroutineContext.cancel()
        ServiceLocator.voiceSessionManager.stop()
        Logger.i(TAG, "Foreground service destroyed")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MyraForeground"
        const val ACTION_STOP = "com.myra.assistant.STOP"

        fun start(context: Context) {
            val intent = Intent(context, MyraForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MyraForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}
