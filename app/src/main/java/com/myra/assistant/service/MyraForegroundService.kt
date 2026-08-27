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
 * Always-on foreground service that keeps the Gemini Live session alive in the
 * background and hosts the hands-free / continuous-listening loop.
 */
class MyraForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServiceLocator.init(applicationContext)
            ServiceLocator.voiceSessionManager.stop()
            return START_STICKY
        }
        startAsForeground()
        // On Android 14+ a microphone FGS crashes if the permission is missing.
        // Fail safe: stop cleanly and let the UI surface the missing permission.
        if (!PermissionHelper.hasPermission(this, Manifest.permission.RECORD_AUDIO)) {
            Logger.w(TAG, "Microphone permission missing; stopping foreground service")
            stopSelf()
            return START_NOT_STICKY
        }
        if (PermissionHelper.hasPermission(this, Manifest.permission.RECORD_AUDIO)) {
            ServiceLocator.voiceSessionManager.startWakeWord()
        }
        Logger.i(TAG, "Foreground service started; wake word listener ready")
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
        ServiceLocator.voiceSessionManager.shutdown()
        Logger.i(TAG, "Foreground service destroyed")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MyraForeground"
        const val ACTION_STOP = "com.myra.assistant.STOP"

        fun start(context: Context) {
            val intent = Intent(context, MyraForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MyraForegroundService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
