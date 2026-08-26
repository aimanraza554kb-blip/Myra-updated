package com.myra.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.myra.assistant.data.ServiceLocator
import com.myra.assistant.util.Logger

/**
 * Monitors phone call state so MYRA can pause the microphone / playback while a
 * real call is in progress and resume afterwards.
 */
class CallMonitorReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        ServiceLocator.init(context)
        val session = ServiceLocator.voiceSessionManager
        // Only intervene while a MYRA session is actually running.
        if (!session.active.value) return
        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING,
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                // Pause the mic for the call, but only if the user hadn't already
                // muted it, so we can restore the exact previous state afterwards.
                if (!session.micMuted.value) {
                    mutedForCall = true
                    session.toggleMic()
                    Logger.d(TAG, "Call active - mic paused")
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (mutedForCall && session.micMuted.value) {
                    mutedForCall = false
                    session.toggleMic()
                    Logger.d(TAG, "Call ended - mic resumed")
                }
            }
        }
    }

    companion object {
        private const val TAG = "CallMonitor"
        @Volatile
        private var mutedForCall = false
    }
}
