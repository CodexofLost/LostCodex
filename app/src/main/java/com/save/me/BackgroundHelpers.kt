package com.save.me

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.location.Location
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.SurfaceHolder
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object CameraBackgroundHelper {

    suspend fun takePhotoOrVideo(
        context: Context,
        surfaceHolder: SurfaceHolder,
        type: String,
        cameraFacing: String,
        flash: Boolean = false,
        videoQuality: Int = 720,
        durationSec: Int = 60,
        file: File
    ): Boolean = withContext(Dispatchers.Main) {
        if (type == "photo") {
            takePhotoSmart(context, surfaceHolder, cameraFacing, flash, file)
        } else {
            recordVideoSmart(context, surfaceHolder, cameraFacing, flash, videoQuality, durationSec, file)
        }
    }

    private fun getCameraId(cameraManager: CameraManager, facing: String): String? {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facingValue = chars.get(CameraCharacteristics.LENS_FACING)
            if ((facing.equals("front", true) && facingValue == CameraCharacteristics.LENS_FACING_FRONT) ||
                ((facing.equals("rear", true) || facing.equals("back", true)) && facingValue == CameraCharacteristics.LENS_FACING_BACK)
            ) {
                return id
            }
        }
        return cameraManager.cameraIdList.firstOrNull()
    }

    private fun getBestJpegSize(cameraManager: CameraManager, cameraId: String): Size {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val configMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val outputSizes = configMap?.getOutputSizes(ImageFormat.JPEG)
        return outputSizes?.let { sizes ->
            sizes.find { it.width == 1280 && it.height == 720 }
                ?: sizes.find { it.width == 1920 && it.height == 1080 }
                ?: sizes.maxByOrNull { it.width * it.height }!!
        } ?: Size(1280, 720)
    }

    /**
     * Improved photo capture: waits for preview and AE convergence (esp. with flash), then triggers capture.
     */
    suspend fun takePhotoSmart(
        context: Context,
        surfaceHolder: SurfaceHolder,
        cameraFacing: String,
        flash: Boolean,
        file: File
    ): Boolean = withContext(Dispatchers.Main) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = getCameraId(cameraManager, cameraFacing) ?: return@withContext false
        var cameraDevice: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var imageReader: android.media.ImageReader? = null
        val handlerThread = HandlerThread("photo_thread").apply { start() }
        val handler = Handler(handlerThread.looper)
        val future = CompletableDeferred<Boolean>()

        val jpegSize = getBestJpegSize(cameraManager, cameraId)

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    imageReader = android.media.ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 1)
                    val targets = listOf(surfaceHolder.surface, imageReader!!.surface)
                    device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(sess: CameraCaptureSession) {
                            session = sess
                            val previewRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            previewRequest.addTarget(surfaceHolder.surface)
                            // Step 1: Start preview and wait for a few frames for resources to be ready
                            sess.setRepeatingRequest(previewRequest.build(), object : CameraCaptureSession.CaptureCallback() {
                                private var frameCount = 0
                                override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, result: TotalCaptureResult) {
                                    frameCount++
                                    if (frameCount == 1) {
                                        // If you want to be extra safe, wait for 2-3 frames
                                        handler.postDelayed({
                                            if (flash && (cameraFacing.equals("rear", true) || cameraFacing.equals("back", true))) {
                                                triggerAeAndCapture(device, sess, imageReader!!, file, handler, future)
                                            } else {
                                                triggerCapture(device, sess, imageReader!!, file, flash, handler, future)
                                            }
                                        }, 200) // 200ms after first frame, or adjust as needed
                                    }
                                }
                            }, handler)
                        }
                        override fun onConfigureFailed(sess: CameraCaptureSession) { future.complete(false) }
                    }, handler)
                }
                override fun onDisconnected(device: CameraDevice) { future.complete(false) }
                override fun onError(device: CameraDevice, error: Int) { future.complete(false) }
            }, handler)
            future.await()
        } catch (e: Exception) {
            Log.e("CameraBackgroundHelper", "Photo error: $e")
            false
        } finally {
            try { cameraDevice?.close() } catch (_: Exception) {}
            try { session?.close() } catch (_: Exception) {}
            try { imageReader?.close() } catch (_: Exception) {}
            handlerThread.quitSafely()
        }
    }

    /**
     * Step 2: For flash, run AE precapture first, then capture
     */
    private fun triggerAeAndCapture(
        device: CameraDevice,
        sess: CameraCaptureSession,
        imageReader: android.media.ImageReader,
        file: File,
        handler: Handler,
        future: CompletableDeferred<Boolean>
    ) {
        val precaptureRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        precaptureRequest.addTarget(imageReader.surface)
        precaptureRequest.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
        sess.capture(precaptureRequest.build(), object : CameraCaptureSession.CaptureCallback() {
            var aeTriggered = false
            override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) {
                checkAeState(partialResult)
            }
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                checkAeState(result)
            }
            fun checkAeState(result: CaptureResult) {
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                if (!aeTriggered && (aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED || aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED)) {
                    aeTriggered = true
                    // Now issue the actual still capture
                    triggerCapture(device, sess, imageReader, file, true, handler, future)
                }
            }
        }, handler)
    }

    /**
     * Step 3: Actually capture the photo
     */
    private fun triggerCapture(
        device: CameraDevice,
        sess: CameraCaptureSession,
        imageReader: android.media.ImageReader,
        file: File,
        flash: Boolean,
        handler: Handler,
        future: CompletableDeferred<Boolean>
    ) {
        val stillRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        stillRequest.addTarget(imageReader.surface)
        if (flash) {
            stillRequest.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
        }
        sess.capture(stillRequest.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s: CameraCaptureSession,
                req: CaptureRequest,
                result: TotalCaptureResult
            ) {
                try {
                    val img = imageReader.acquireLatestImage()
                    if (img != null) {
                        val buffer = img.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        file.writeBytes(bytes)
                        img.close()
                        future.complete(true)
                    } else {
                        Log.e("CameraBackgroundHelper", "Image is null (no data from ImageReader)")
                        future.complete(false)
                    }
                } catch (e: Exception) {
                    Log.e("CameraBackgroundHelper", "Exception saving JPEG: $e")
                    future.complete(false)
                }
                try { s.close() } catch (_: Exception) {}
                try { device.close() } catch (_: Exception) {}
                try { imageReader.close() } catch (_: Exception) {}
            }
        }, handler)
    }

    /**
     * Improved video: wait for preview and resources before recording
     */
    suspend fun recordVideoSmart(
        context: Context,
        surfaceHolder: SurfaceHolder,
        cameraFacing: String,
        flash: Boolean,
        videoQuality: Int,
        durationSec: Int,
        file: File
    ): Boolean = withContext(Dispatchers.Main) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = getCameraId(cameraManager, cameraFacing) ?: return@withContext false
        var cameraDevice: CameraDevice? = null
        var session: CameraCaptureSession? = null
        val handlerThread = HandlerThread("video_thread").apply { start() }
        val handler = Handler(handlerThread.looper)

        val (width, height) = when (videoQuality) {
            1080 -> 1920 to 1080
            480 -> 640 to 480
            else -> 1280 to 720
        }

        val recorder = MediaRecorder()
        val future = CompletableDeferred<Boolean>()
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(file.absolutePath)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setVideoFrameRate(30)
            recorder.setVideoSize(width, height)
            recorder.prepare()

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    val previewSurface = surfaceHolder.surface
                    val recorderSurface = recorder.surface

                    device.createCaptureSession(
                        listOf(previewSurface, recorderSurface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(sess: CameraCaptureSession) {
                                session = sess
                                val previewRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                previewRequest.addTarget(previewSurface)
                                // 1. Start preview stream and wait for a few frames
                                sess.setRepeatingRequest(previewRequest.build(), object : CameraCaptureSession.CaptureCallback() {
                                    private var frameCount = 0
                                    override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, result: TotalCaptureResult) {
                                        frameCount++
                                        if (frameCount == 2) { // wait for 2 preview frames
                                            // 2. Start recording
                                            val recordRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                            recordRequest.addTarget(recorderSurface)
                                            recordRequest.addTarget(previewSurface)
                                            if (flash && (cameraFacing.equals("rear", true) || cameraFacing.equals("back", true))) {
                                                recordRequest.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                                            }
                                            try {
                                                sess.setRepeatingRequest(recordRequest.build(), null, handler)
                                                recorder.start()
                                                handler.postDelayed({
                                                    try {
                                                        recorder.stop()
                                                        recorder.release()
                                                        sess.stopRepeating()
                                                        future.complete(true)
                                                    } catch (e: Exception) {
                                                        Log.e("CameraBackgroundHelper", "Error stopping video: $e")
                                                        future.complete(false)
                                                    }
                                                    try { sess.close() } catch (_: Exception) {}
                                                    try { device.close() } catch (_: Exception) {}
                                                    handlerThread.quitSafely()
                                                }, (durationSec * 1000).toLong())
                                            } catch (e: Exception) {
                                                Log.e("CameraBackgroundHelper", "Error starting recorder: $e")
                                                future.complete(false)
                                                try { sess.close() } catch (_: Exception) {}
                                                try { device.close() } catch (_: Exception) {}
                                                handlerThread.quitSafely()
                                            }
                                        }
                                    }
                                }, handler)
                            }
                            override fun onConfigureFailed(sess: CameraCaptureSession) { future.complete(false) }
                        },
                        handler
                    )
                }
                override fun onDisconnected(device: CameraDevice) { future.complete(false) }
                override fun onError(device: CameraDevice, error: Int) { future.complete(false) }
            }, handler)
            future.await()
        } catch (e: Exception) {
            Log.e("CameraBackgroundHelper", "Video error: $e")
            false
        } finally {
            try { cameraDevice?.close() } catch (_: Exception) {}
            try { session?.close() } catch (_: Exception) {}
            try { recorder.release() } catch (_: Exception) {}
            handlerThread.quitSafely()
        }
    }
}

object AudioBackgroundHelper {
    suspend fun recordAudio(context: Context, outputFile: File, durationSec: Int) {
        Log.d("DURATION_DEBUG", "AudioBackgroundHelper: requested durationSec=$durationSec")
        val recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }
        val start = System.currentTimeMillis()
        try {
            delay(durationSec * 1000L)
        } finally {
            val actualElapsed = (System.currentTimeMillis() - start) / 1000
            Log.d("DURATION_DEBUG", "AudioBackgroundHelper: delay ended, actualElapsed=$actualElapsed seconds")
            recorder.stop()
            recorder.release()
        }
    }
}

object LocationBackgroundHelper {
    suspend fun getLastLocation(context: Context, timeoutMillis: Long = 10000L): Location? {
        val fused = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context)
        try {
            val lastKnown = suspendCoroutine<Location?> { cont ->
                fused.lastLocation
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
            }
            if (lastKnown != null) return lastKnown
        } catch (_: Exception) {}

        return try {
            suspendCoroutine<Location?> { cont ->
                val request = com.google.android.gms.location.LocationRequest
                    .Builder(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 0)
                    .setMaxUpdates(1)
                    .setDurationMillis(timeoutMillis)
                    .build()
                val callback = object : com.google.android.gms.location.LocationCallback() {
                    override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                        cont.resume(result.lastLocation)
                        fused.removeLocationUpdates(this)
                    }
                    override fun onLocationAvailability(availability: com.google.android.gms.location.LocationAvailability) {
                        if (!availability.isLocationAvailable) {
                            cont.resume(null)
                            fused.removeLocationUpdates(this)
                        }
                    }
                }
                fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        cont.resume(null)
                        fused.removeLocationUpdates(callback)
                    } catch (_: Exception) {}
                }, timeoutMillis)
            }
        } catch (e: Exception) {
            Log.e("LocationBackgroundHelper", "Location update failed: $e")
            null
        }
    }
}