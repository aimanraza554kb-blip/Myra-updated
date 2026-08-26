package com.myra.assistant.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.myra.assistant.util.Logger

/**
 * Listens to incoming notifications so MYRA can read/announce them in hands-free
 * mode. The latest notification text is kept in memory for the assistant.
 */
class MyraNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        if (title.isNotEmpty() || text.isNotEmpty()) {
            lastNotification = "$title: $text"
            Logger.d(TAG, "Notification: $lastNotification")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    companion object {
        private const val TAG = "MyraNotifListener"
        @Volatile
        var lastNotification: String = ""
            private set
    }
}
