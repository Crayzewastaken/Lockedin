package com.lockedin.morning

import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Remembers when each app last posted a notification. During a session, a messaging app
 * may only be opened shortly after it actually received a message — so you can reply,
 * but can't use "checking messages" as a way to scroll.
 */
class NotificationWatcher : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.isOngoing) return
        applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(sbn.packageName, System.currentTimeMillis()).apply()
    }

    companion object {
        private const val PREFS = "notif_times"

        fun lastPosted(ctx: Context, pkg: String): Long =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(pkg, 0L)

        fun isEnabled(ctx: Context): Boolean =
            (Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: "")
                .contains(ctx.packageName)
    }
}
