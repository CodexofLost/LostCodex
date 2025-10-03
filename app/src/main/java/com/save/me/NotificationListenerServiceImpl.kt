package com.save.me

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

class NotificationListenerServiceImpl : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val context = applicationContext

        val titleAny = sbn.notification.extras.get("android.title")
        val textAny = sbn.notification.extras.get("android.text")
        val title: String = when (titleAny) {
            is String -> titleAny
            is CharSequence -> titleAny.toString()
            else -> ""
        }
        val text: String = when (textAny) {
            is String -> textAny
            is CharSequence -> textAny.toString()
            else -> ""
        }
        val time = sbn.postTime
        val channelId = sbn.notification.channelId ?: ""
        val isOngoing = sbn.isOngoing
        val packageName = sbn.packageName

        storeNotification(
            context,
            packageName,
            title,
            text,
            time,
            channelId,
            isOngoing
        )
    }

    companion object {
        private const val PREFS_NAME = "noti_history_prefs_json"
        private const val MAX_HISTORY = 1000

        /**
         * Store a notification in JSON history for a package.
         */
        fun storeNotification(
            context: Context,
            pkg: String,
            title: String,
            text: String,
            time: Long,
            channelId: String,
            isOngoing: Boolean
        ) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val key = "history_$pkg"
            val history = prefs.getString(key, null)
            val arr = if (history != null && history.isNotBlank()) {
                try {
                    JSONArray(history)
                } catch (_: Exception) {
                    JSONArray()
                }
            } else {
                JSONArray()
            }

            // Deduplicate: check last entry to avoid consecutive duplicates
            if (arr.length() > 0) {
                val last = arr.getJSONObject(0)
                if (
                    last.optString("title") == title &&
                    last.optString("text") == text &&
                    last.optLong("time") == time
                ) {
                    return // skip consecutive duplicate
                }
            }

            val obj = JSONObject().apply {
                put("title", title)
                put("text", text)
                put("time", time)
                put("channelId", channelId)
                put("isOngoing", isOngoing)
            }

            // Insert at front (newest first)
            val newArr = JSONArray()
            newArr.put(obj)
            for (i in 0 until minOf(arr.length(), MAX_HISTORY - 1)) {
                newArr.put(arr.getJSONObject(i))
            }

            prefs.edit().putString(key, newArr.toString()).apply()
        }

        /**
         * Get notification history for a package.
         * Returns a list of JSONObjects.
         */
        fun getHistoryForPackage(context: Context, pkg: String): List<JSONObject> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val key = "history_$pkg"
            val history = prefs.getString(key, null) ?: return emptyList()
            val arr = try {
                JSONArray(history)
            } catch (_: Exception) {
                return emptyList()
            }
            return List(arr.length()) { arr.getJSONObject(it) }
        }

        /**
         * For compatibility with code expecting Triple<String, String, Long>, provide a mapping.
         */
        fun getHistoryTriplesForPackage(context: Context, pkg: String): List<Triple<String, String, Long>> {
            return getHistoryForPackage(context, pkg).map {
                Triple(
                    it.optString("title", ""),
                    it.optString("text", ""),
                    it.optLong("time", 0L)
                )
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
