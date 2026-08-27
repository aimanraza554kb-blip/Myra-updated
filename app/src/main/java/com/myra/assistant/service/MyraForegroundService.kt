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

/**
 * Foreground service that stays alive for hands-free wake-word listening.
 *
 * When Wake Word is enabled, Gemini is NOT started until "Hey MYRA" is heard.
 * The wake recognizer owns the microphone while waiting. Once the wake phrase
 * is detected it releases the microphone and starts the normal Gemini session.
 */
class MyraForegroundService : Service() {

    private var wakeWordListener: MyraWakeWordListener? = null

    override fun onCreate() {
        super.onCreate()
        // When the Gemini session is stopped from anywhere in the app, return
        // immediately to wake-word mode if the feature is enabled.
        ServiceLocator.voiceSessionManager.onSessionStopped = {
            if (ServiceLocator.settingsRepository.wakeWordEnabled()) {
                startWakeWordMode()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()

        if (!PermissionHelper.hasPermission(this, Manifest.permission.RECORD_AUDIO)) {
            Logger.w(TAG, "Microphone permission missing; stopping foreground service")
            stopSelf()
            return START_NOT_STICKY
        }

        if (ServiceLocator.settingsRepository.wakeWordEnabled()) {
            // Wake mode is independent of Gemini's active state.
            if (!ServiceLocator.voiceSessionManager.active.value) {
                startWakeWordMode()
            }
        } else {
            // Preserve the existing behavior when wake word is disabled.
            ServiceLocator.voiceSessionManager.start()
        }

        Logger.i(TAG, "Foreground service started")
        return START_STICKY
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                Constants.NOTIFICATION_ID_FOREGROUND,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(Constants.NOTIFICATION_ID_FOREGROUND, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Deliberately no Start/Stop action here: with Wake Word enabled the
        // service must remain alive so "Hey MYRA" can wake Gemini while MYRA is idle.
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

    private fun startWakeWordMode() {
        if (!ServiceLocator.settingsRepository.wakeWordEnabled()) return
        if (ServiceLocator.voiceSessionManager.active.value) return
        if (wakeWordListener != null) return

        wakeWordListener = MyraWakeWordListener(
            context = this,
            languageTag = ServiceLocator.settingsRepository.language(),
            onWakeWord = {
                // Release the microphone before Gemini's AudioRecorder starts.
                wakeWordListener?.stop()
                wakeWordListener = null

                Logger.i(TAG, "Wake word detected; starting MYRA immediately")

                // No TTS acknowledgement and no artificial delay. Gemini starts
                // immediately after the wake recognizer releases the microphone.
                android.os.Handler(mainLooper).post {
                    ServiceLocator.voiceSessionManager.start()
                }
            }
        ).also { it.start() }
    }

    override fun onDestroy() {
        wakeWordListener?.stop()
        wakeWordListener = null
        ServiceLocator.voiceSessionManager.onSessionStopped = null
        ServiceLocator.voiceSessionManager.stop()
        Logger.i(TAG, "Foreground service destroyed")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MyraForeground"

        fun start(context: Context) {
            val intent = Intent(context, MyraForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            // Kept for compatibility with existing callers. The wake-word UI no
            // longer exposes a Start/Stop control when Wake Word is enabled.
            context.stopService(
                Intent(context, MyraForegroundService::class.java)
            )
        }
    }
}
