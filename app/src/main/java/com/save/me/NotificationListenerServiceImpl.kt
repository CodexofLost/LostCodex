package com.save.me

import android.content.Context
import android.content.SharedPreferences
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import java.util.concurrent.ConcurrentHashMap

class NotificationListenerServiceImpl : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val context = applicationContext
        val pkg = sbn.packageName
        val title = sbn.notification.extras.getString("android.title") ?: ""
        val text = sbn.notification.extras.getString("android.text") ?: ""
        val time = sbn.postTime

        storeNotification(context, pkg, title, text, time)
    }

    companion object {
        private const val PREFS_NAME = "noti_history_prefs"
        private const val MAX_HISTORY = 100

        /**
         * Store a notification in history for a package.
         */
        fun storeNotification(context: Context, pkg: String, title: String, text: String, time: Long) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val key = "history_$pkg"
            val history = prefs.getString(key, "") ?: ""
            val entries = if (history.isNotEmpty()) history.split("|||").toMutableList() else mutableListOf()
            // Store as title:::text:::time
            val entry = "${title.replace('|', ' ')}:::${text.replace('|', ' ')}:::$time"
            entries.add(0, entry)
            while (entries.size > MAX_HISTORY) entries.removeAt(entries.size - 1)
            prefs.edit().putString(key, entries.joinToString("|||")).apply()
        }

        /**
         * Get notification history for a package.
         * Returns a list of Triple<title, text, time>.
         */
        fun getHistoryForPackage(context: Context, pkg: String): List<Triple<String, String, Long>> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val key = "history_$pkg"
            val history = prefs.getString(key, "") ?: ""
            if (history.isEmpty()) return emptyList()
            return history.split("|||").mapNotNull { entry ->
                val parts = entry.split(":::")
                if (parts.size == 3) {
                    val title = parts[0]
                    val text = parts[1]
                    val time = parts[2].toLongOrNull() ?: 0L
                    Triple(title, text, time)
                } else null
            }
        }

        /**
         * Clear all notification history for a package.
         */
        fun clearNotificationsForPackage(context: Context, pkg: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val key = "history_$pkg"
            prefs.edit().remove(key).apply()
        }
    }
}