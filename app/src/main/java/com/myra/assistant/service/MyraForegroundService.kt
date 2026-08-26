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
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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

    private var wakeWordListener: MyraWakeWordListener? = null
    private var wakeReplyTts: TextToSpeech? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        // On Android 14+ a microphone FGS crashes if the permission is missing.
        // Fail safe: stop cleanly and let the UI surface the missing permission.
        if (!PermissionHelper.hasPermission(this, Manifest.permission.RECORD_AUDIO)) {
            Logger.w(TAG, "Microphone permission missing; stopping foreground service")
            stopSelf()
            return START_NOT_STICKY
        }
        if (ServiceLocator.settingsRepository.wakeWordEnabled()) {
            startWakeWordMode()
        } else {
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
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MyraForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, Constants.CHANNEL_FOREGROUND)
            .setContentTitle(getString(R.string.fgs_notification_title))
            .setContentText(getString(R.string.fgs_notification_text))
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun acknowledgeWakeAndStartSession() {
        val locale = runCatching {
            java.util.Locale.forLanguageTag(ServiceLocator.settingsRepository.language())
        }.getOrDefault(java.util.Locale.US)

        val tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                startGeminiAfterWake()
                return@TextToSpeech
            }

            wakeReplyTts?.language = locale
            wakeReplyTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    startGeminiAfterWake()
                }

                override fun onError(utteranceId: String?) {
                    startGeminiAfterWake()
                }
            })

            val result = wakeReplyTts?.speak(
                "Yes?",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "MYRA_WAKE_ACK"
            ) ?: TextToSpeech.ERROR

            if (result == TextToSpeech.ERROR) startGeminiAfterWake()
        }
        wakeReplyTts = tts
    }

    private fun startGeminiAfterWake() {
        android.os.Handler(mainLooper).post {
            ServiceLocator.voiceSessionManager.start()
        }
    }

    private fun startWakeWordMode() {
        if (wakeWordListener != null) return

        wakeWordListener = MyraWakeWordListener(
            context = this,
            languageTag = ServiceLocator.settingsRepository.language(),
            onWakeWord = {
                // Stop wake recognition before Gemini takes the microphone.
                wakeWordListener?.stop()
                wakeWordListener = null

                Logger.i(TAG, "Wake word detected; acknowledging and starting MYRA")
                acknowledgeWakeAndStartSession()
            }
        ).also { it.start() }
    }

    override fun onDestroy() {
        wakeWordListener?.stop()
        wakeWordListener = null
        wakeReplyTts?.stop()
        wakeReplyTts?.shutdown()
        wakeReplyTts = null
        ServiceLocator.voiceSessionManager.stop()
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
