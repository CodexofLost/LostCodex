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
        val duration = remoteMessage.data["duration"]
        val chatId = remoteMessage.data["chat_id"] ?: remoteMessage.data["chatId"]

        if (type != null && chatId != null) {
            Log.d(
                "FCMService",
                "Dispatching action: type=$type, camera=$camera, flash=$flash, quality=$quality, duration=$duration, chatId=$chatId"
            )
            CommandManager.dispatch(
                applicationContext,
                type = type,
                camera = camera,
                flash = flash,
                quality = quality,
                duration = duration,
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