package com.save.me

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class QueuedCommand(
    val type: String,
    val camera: String? = null,
    val flash: String? = null,
    val quality: String? = null,
    val duration: String? = null,
    val chatId: String? = null
)

/**
 * ActionHandlers only enqueues commands;
 * all actual execution happens through CommandQueueManager.
 */
object ActionHandlers {
    fun dispatch(
        context: Context,
        type: String,
        camera: String? = null,
        flash: String? = null,
        quality: String? = null,
        duration: String? = null,
        chatId: String? = null
    ) {
        // Initialize CommandQueueManager
        CommandQueueManager.initialize(context)

        val deviceNickname = Preferences.getNickname(context) ?: "Device"
        if (!chatId.isNullOrBlank()) {
            val ackMsg = "[$deviceNickname] Command received: $type"
            UploadManager.init(context)
            UploadManager.sendTelegramMessage(chatId, ackMsg)
        }

        // Enqueue the command for persistent, resource-aware execution
        CommandQueueManager.enqueue(
            QueuedCommand(
                type, camera, flash, quality, duration, chatId
            )
        )
    }

    /**
     * Executes a remote command.
     * This is called only by the CommandQueueManager, never directly.
     */
    suspend fun executeCommand(context: Context, command: QueuedCommand) {
        val (type, camera, flash, quality, duration, chatId) = command
        val deviceNickname = Preferences.getNickname(context) ?: "Device"

        if (!checkPermissions(context, type)) {
            Log.e("ActionHandlers", "Required permissions not granted for $type")
            NotificationHelper.showNotification(
                context, "Permission Error",
                "Required permissions not granted for $type. Please allow all required permissions in system settings."
            )
            if (!chatId.isNullOrBlank()) {
                UploadManager.sendTelegramMessage(chatId, "[$deviceNickname] Error: Required permissions not granted for $type.")
            }
            return
        }

        if (Build.VERSION.SDK_INT >= 34) {
            if (type == "photo" || type == "video") {
                if (!OverlayHelper.hasOverlayPermission(context)) {
                    OverlayHelper.requestOverlayPermission(context)
                    NotificationHelper.showNotification(
                        context,
                        "Permission Needed",
                        "Grant 'Display over other apps' for full background capability."
                    )
                    return
                }
                val deferred = CompletableDeferred<Unit>()
                OverlayHelper.showSurfaceOverlay(
                    context,
                    callback = { surfaceHolder, overlayView ->
                        startCameraActionInvoke(
                            context,
                            type,
                            camera,
                            flash,
                            quality,
                            duration,
                            chatId,
                            surfaceHolder,
                            overlayView
                        )
                        deferred.complete(Unit)
                    },
                    overlaySizeDp = 64,
                    offScreen = true
                )
                deferred.await()
                return
            } else {
                if (OverlayHelper.hasOverlayPermission(context)) {
                    val deferred = CompletableDeferred<Unit>()
                    OverlayHelper.showViewOverlay(context, callback = { overlayView ->
                        if (overlayView == null) {
                            Log.e("ActionHandlers", "Audio/location overlay creation failed.")
                            deferred.complete(Unit)
                            return@showViewOverlay
                        }
                        Handler(Looper.getMainLooper()).postDelayed({
                            OverlayHelper.removeOverlay(context, overlayView)
                            startOtherActionInvoke(context, type, duration, chatId)
                            deferred.complete(Unit)
                        }, 400)
                    }, overlaySizeDp = 64, offScreen = true)
                    deferred.await()
                    return
                }
            }
        } else {
            startCameraActionInvoke(context, type, camera, flash, quality, duration, chatId)
        }
        startOtherActionInvoke(context, type, duration, chatId)
    }

    private fun startCameraActionInvoke(
        context: Context,
        type: String,
        camera: String?,
        flash: String?,
        quality: String?,
        duration: String?,
        chatId: String?,
        surfaceHolder: SurfaceHolder? = null,
        overlayView: View? = null
    ) {
        val cam = camera ?: "front"
        val flashEnabled = flash == "true"
        val qualityInt = quality?.filter { it.isDigit() }?.toIntOrNull() ?: if (type == "photo") 1080 else 480
        val durationInt = duration?.toIntOrNull() ?: if (type == "photo") 0 else 60

        ForegroundActionService.startCameraAction(
            context,
            type,
            JSONObject().apply {
                put("camera", cam)
                put("flash", flashEnabled)
                put("quality", qualityInt)
                put("duration", durationInt)
            },
            chatId
        )
    }

    private fun startOtherActionInvoke(
        context: Context,
        type: String,
        duration: String?,
        chatId: String?
    ) {
        when (type) {
            "audio" -> {
                val durationInt = duration?.toIntOrNull() ?: 60
                ForegroundActionService.startAudioAction(
                    context,
                    JSONObject().apply {
                        put("duration", durationInt)
                    },
                    chatId
                )
            }
            "location" -> {
                ForegroundActionService.startLocationAction(context, chatId)
            }
            "ring" -> {
                ForegroundActionService.startRingAction(context, JSONObject())
            }
            "vibrate" -> {
                ForegroundActionService.startVibrateAction(context, JSONObject())
            }
            else -> {
                NotificationHelper.showNotification(context, "Unknown Action", "Action $type is not supported.")
                Log.w("ActionHandlers", "Unknown action type: $type")
                if (!chatId.isNullOrBlank()) {
                    val deviceNickname = Preferences.getNickname(context) ?: "Device"
                    val errMsg = "[$deviceNickname] Error: Action $type is not supported."
                    UploadManager.sendTelegramMessage(chatId, errMsg)
                }
            }
        }
    }

    private fun checkPermissions(context: Context, type: String): Boolean {
        fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        if (type == "photo") {
            return has(android.Manifest.permission.CAMERA) &&
                    (Build.VERSION.SDK_INT < 34 || has(android.Manifest.permission.FOREGROUND_SERVICE_CAMERA))
        }
        if (type == "video") {
            return has(android.Manifest.permission.CAMERA) &&
                    has(android.Manifest.permission.RECORD_AUDIO) &&
                    (Build.VERSION.SDK_INT < 34 || has(android.Manifest.permission.FOREGROUND_SERVICE_CAMERA)) &&
                    (Build.VERSION.SDK_INT < 34 || has(android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE))
        }
        if (type == "audio") {
            return has(android.Manifest.permission.RECORD_AUDIO) &&
                    (Build.VERSION.SDK_INT < 34 || has(android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE))
        }
        if (type == "location") {
            return (has(android.Manifest.permission.ACCESS_FINE_LOCATION) ||
                    has(android.Manifest.permission.ACCESS_COARSE_LOCATION)) &&
                    (Build.VERSION.SDK_INT < 34 || has(android.Manifest.permission.FOREGROUND_SERVICE_LOCATION))
        }
        return true
    }
}