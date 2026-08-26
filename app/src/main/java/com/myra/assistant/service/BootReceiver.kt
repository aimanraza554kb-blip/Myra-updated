package com.myra.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.myra.assistant.data.ServiceLocator
import com.myra.assistant.util.Logger

/**
 * Restarts MYRA's always-on foreground service after the device reboots, if the
 * user enabled continuous / hands-free mode.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ServiceLocator.init(context)
            val settings = ServiceLocator.settingsRepository
            if (settings.continuousListening() || settings.handsFree() || settings.wakeWordEnabled()) {
                MyraForegroundService.start(context)
                Logger.i(TAG, "Restarted after boot")
            }
            if (settings.overlayEnabled()) {
                FloatingBubbleService.start(context)
            }
        }
    }

    companion object { private const val TAG = "BootReceiver" }
}
