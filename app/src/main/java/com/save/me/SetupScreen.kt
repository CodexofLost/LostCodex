package com.save.me

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.save.me.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit
) {
    AppTheme {
        val context = LocalContext.current
        var botToken by remember {
            mutableStateOf(
                TextFieldValue(
                    Preferences.getBotToken(context) ?: ""
                )
            )
        }
        var nickname by remember {
            mutableStateOf(
                TextFieldValue(
                    Preferences.getNickname(context) ?: ""
                )
            )
        }
        var error by remember { mutableStateOf<String?>(null) }
        var webhookStatus by remember { mutableStateOf<String?>(null) }
        var webhookInProgress by remember { mutableStateOf(false) }
        val coroutineScope = rememberCoroutineScope()

        fun saveSetup(context: Context, token: String, nickname: String) {
            Preferences.setBotToken(context, token)
            Preferences.setNickname(context, nickname)
        }

        val backgroundColor = MaterialTheme.colorScheme.background

        // CORRECTED: Set webhook to /bot<TOKEN> path!
        suspend fun setTelegramWebhook(token: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
            try {
                val webhookUrl = "https://findmydevice.kambojistheking.workers.dev/bot${token}"
                val url = "https://api.telegram.org/bot${token}/setWebhook?url=${webhookUrl}"
                val client = OkHttpClient()
                val request = Request.Builder().url(url).get().build()
                val response: Response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""
                if (response.isSuccessful && body.contains("\"ok\":true")) {
                    true to "Webhook set successfully!"
                } else {
                    false to "Failed to set webhook: $body"
                }
            } catch (e: Exception) {
                false to "Webhook error: ${e.localizedMessage ?: "Unknown error"}"
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .padding(24.dp)
        ) {
            Text(
                "Setup Device",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = botToken,
                onValueChange = { botToken = it },
                label = { Text("Bot Token") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurface,
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            )

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    webhookStatus = null
                    error = null
                    if (botToken.text.isBlank()) {
                        error = "Please enter the Bot Token before setting webhook."
                        return@Button
                    }
                    webhookInProgress = true
                    coroutineScope.launch {
                        val (ok, msg) = setTelegramWebhook(botToken.text.trim())
                        webhookInProgress = false
                        webhookStatus = msg
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !webhookInProgress
            ) {
                if (webhookInProgress) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("Set Webhook")
            }
            webhookStatus?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    it,
                    color = if (it.contains("success", true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text("Device Nickname") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurface,
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    if (botToken.text.isBlank() || nickname.text.isBlank()) {
                        error = "Please enter both Bot Token and Nickname."
                    } else {
                        saveSetup(context, botToken.text, nickname.text)
                        coroutineScope.launch {
                            // If bot token changed, auto-set webhook again for user convenience
                            val oldToken = Preferences.getBotToken(context)
                            if (botToken.text.trim() != (oldToken ?: "")) {
                                webhookInProgress = true
                                val (ok, msg) = setTelegramWebhook(botToken.text.trim())
                                webhookInProgress = false
                                webhookStatus = msg
                            }
                            onSetupComplete()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !webhookInProgress
            ) {
                Text("Save & Continue")
            }

            error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}