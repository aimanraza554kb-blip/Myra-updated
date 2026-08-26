package com.myra.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.myra.assistant.data.ServiceLocator
import com.myra.assistant.util.Logger

/**
 * Reacts to screen on/off (power button) events. When hands-free mode is on we
 * keep the assistant ready; this is also where a double-press launch gesture can
 * be wired to open MYRA.
 */
class PowerButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> Logger.d(TAG, "Screen on")
            Intent.ACTION_SCREEN_OFF -> {
                ServiceLocator.init(context)
                if (!ServiceLocator.settingsRepository.handsFree()) {
                    Logger.d(TAG, "Screen off")
                }
            }
        }
    }

    companion object { private const val TAG = "PowerButtonReceiver" }
}
