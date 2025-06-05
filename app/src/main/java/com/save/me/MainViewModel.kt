package com.save.me

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.save.me.ForegroundActionService

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _serviceActive = mutableStateOf(false)
    val serviceActive: State<Boolean> get() = _serviceActive

    private val _actionHistory = mutableStateListOf<RemoteAction>()
    val actionHistory: List<RemoteAction> get() = _actionHistory

    private val _actionInProgress = mutableStateOf<RemoteAction?>(null)
    val actionInProgress: State<RemoteAction?> get() = _actionInProgress

    private val _actionError = mutableStateOf<String?>(null)
    val actionError: State<String?> get() = _actionError

    private val _lastError = mutableStateOf<String?>(null)
    val lastError: State<String?> get() = _lastError

    fun getCurrentNickname(): String =
        Preferences.getNickname(getApplication()) ?: "Device"

    fun getCurrentBotToken(): String =
        Preferences.getBotToken(getApplication()) ?: "Not set"

    fun startRemoteAction(type: String, preview: String? = null) {
        val newAction = RemoteAction(
            type = type,
            status = "pending",
            timestamp = System.currentTimeMillis(),
            preview = preview
        )
        _actionInProgress.value = newAction
        _actionError.value = null

        viewModelScope.launch {
            kotlinx.coroutines.delay(1200)
            val success = (0..1).random() == 1
            val completedAction = newAction.copy(
                status = if (success) "success" else "error",
                preview = if (type == "photo" && success) "Photo.jpg" else preview,
                error = if (!success) "Action failed (simulated error)" else null
            )
            _actionHistory.add(0, completedAction)
            _actionInProgress.value = null
            if (!success) {
                _actionError.value = "Failed to run $type action"
                _lastError.value = completedAction.error
            }
            refreshServiceStatus()
        }
    }

    fun clearError() {
        _actionError.value = null
        _lastError.value = null
    }

    fun refreshServiceStatus() {
        _serviceActive.value = isServiceRunning(getApplication(), ForegroundActionService::class.java)
    }

    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in activityManager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
}