package com.save.me

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FCMService : FirebaseMessagingService() {
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        // Only process command if bot_token matches our own
        val incomingBotToken = remoteMessage.data["bot_token"]
        val localBotToken = Preferences.getBotToken(applicationContext)
        if (incomingBotToken != null && incomingBotToken != localBotToken) {
            return
        }

        val type = remoteMessage.data["type"]
        val camera = remoteMessage.data["camera"]
        val flash = remoteMessage.data["flash"]
        val quality = remoteMessage.data["quality"]
        val rawDuration = remoteMessage.data["duration"]
        val chatId = remoteMessage.data["chat_id"] ?: remoteMessage.data["chatId"]

        // Only accept durations as (possibly fractional) minutes, e.g. "1.5" = 90 seconds.
        fun normalizeDurationForDispatch(duration: String?): String? {
            if (duration.isNullOrBlank()) return null
            val cleaned = duration.trim().lowercase()
            if (cleaned.contains("s") || cleaned.contains("sec") || cleaned.contains(":")) {
                return null
            }
            val dVal = cleaned.toDoubleOrNull()
            if (dVal == null || dVal <= 0) return null
            return dVal.toString()
        }

        val normalizedDuration = normalizeDurationForDispatch(rawDuration)

        if (type != null && chatId != null) {
            CommandManager.dispatch(
                applicationContext,
                type = type,
                camera = camera,
                flash = flash,
                quality = quality,
                duration = normalizedDuration,
                chatId = chatId
            )
        }
    }

    override fun onNewToken(token: String) {
        // Optionally handle token updates here.
    }
}