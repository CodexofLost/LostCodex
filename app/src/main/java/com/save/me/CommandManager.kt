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
import androidx.room.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

object CommandManager {
    enum class ResourceGroup { CAMERA, MIC, LOCATION }

    private val queueScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val resourceMutex = Mutex()
    private val runningResources = mutableSetOf<ResourceGroup>()
    private val runningJobIds = mutableSetOf<Long>()
    private lateinit var appContext: Context
    private lateinit var dao: CommandQueueDao
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        dao = CommandQueueDatabase.getInstance(appContext).commandQueueDao()
        initialized = true
        Log.d("CommandManager", "Initialized CommandManager in event-driven mode (resource release on recording/capture end)")
    }

    fun dispatch(
        context: Context,
        type: String,
        camera: String? = null,
        flash: String? = null,
        quality: String? = null,
        duration: String? = null,
        chatId: String? = null
    ) {
        val deviceNickname = Preferences.getNickname(context) ?: "Device"
        if (!chatId.isNullOrBlank()) {
            val ackMsg = "[$deviceNickname] Command received: $type"
            UploadManager.init(context)
            UploadManager.sendTelegramMessage(chatId, ackMsg)
        }

        Log.d("CommandManager", "Dispatch called: type=$type, camera=$camera, flash=$flash, quality=$quality, duration=$duration, chatId=$chatId")
        enqueue(
            QueuedCommand(
                type, camera, flash, quality, duration, chatId
            )
        )
    }

    fun enqueue(command: QueuedCommand) {
        queueScope.launch {
            val id = dao.insert(CommandEntity.fromQueuedCommand(command))
            Log.d("CommandManager", "Enqueued command: $command (db id: $id)")
            val pending = dao.getAllPending()
            Log.d("CommandManager", "Current pending queue: $pending")
            processQueueOnce()
        }
    }

    private suspend fun processQueueOnce() {
        val pending = dao.getAllPending()
        Log.d("CommandManager", "Processing queue (event-driven). runningResources=$runningResources, runningJobIds=$runningJobIds, pendingQueue=$pending")
        if (pending.isEmpty()) {
            Log.d("CommandManager", "Queue is empty; nothing to process.")
            return
        }

        resourceMutex.withLock {
            for (cmd in pending) {
                Log.d("CommandManager", "Checking command $cmd: runningJobIds=$runningJobIds, runningResources=$runningResources")
                if (runningJobIds.contains(cmd.id)) {
                    Log.d("CommandManager", "Skipping $cmd because it is already running.")
                    continue
                }
                val neededResources = getResourceGroups(cmd.type)
                if (neededResources.any { it in runningResources }) {
                    Log.d("CommandManager", "Skipping $cmd because neededResources=$neededResources are in runningResources=$runningResources")
                    continue
                }

                runningResources.addAll(neededResources)
                runningJobIds.add(cmd.id)
                Log.d("CommandManager", "STARTING $cmd, acquired resources $neededResources: runningResources now $runningResources")

                queueScope.launch {
                    try {
                        dao.updateStatus(cmd.id, "running")
                        withContext(Dispatchers.IO) {
                            executeCommand(appContext, cmd.toQueuedCommand(), cmd.id)
                        }
                        // Do NOT mark as done or free resources here!
                        // Wait for onCommandActionComplete to be called.
                    } catch (e: Exception) {
                        dao.updateStatus(cmd.id, "failed")
                        Log.e("CommandManager", "Command failed: ${cmd.type}", e)
                        // Free resources if action failed to start
                        onCommandActionComplete(cmd.id)
                    }
                }
            }
        }
    }

    /**
     * This must be called by ForegroundActionService (or wherever your recording/capture completes)
     * to mark the command as done and free resources.
     */
    fun onCommandActionComplete(commandId: Long) {
        queueScope.launch {
            val cmd = dao.getById(commandId)
            if (cmd != null) {
                val neededResources = getResourceGroups(cmd.type)
                dao.updateStatus(commandId, "done")
                resourceMutex.withLock {
                    runningResources.removeAll(neededResources)
                    runningJobIds.remove(commandId)
                    Log.d("CommandManager", "Freed resources for $cmd: runningResources now $runningResources")
                }
                Log.d("CommandManager", "Command $cmd completed (onCommandActionComplete). Checking queue for next eligible command (event-driven)...")
                processQueueOnce()
            } else {
                Log.w("CommandManager", "onCommandActionComplete: command id $commandId not found")
            }
        }
    }

    fun clearAll() {
        queueScope.launch {
            dao.clearAll()
            resourceMutex.withLock {
                runningResources.clear()
                runningJobIds.clear()
            }
        }
    }

    /**
     * @param commandId Pass the database id of the command so onCommandActionComplete can be called later.
     */
    suspend fun executeCommand(context: Context, command: QueuedCommand, commandId: Long) {
        val (type, camera, flash, quality, duration, chatId) = command
        val deviceNickname = Preferences.getNickname(context) ?: "Device"

        if (!checkPermissions(context, type)) {
            Log.e("CommandManager", "Required permissions not granted for $type")
            NotificationHelper.showNotification(
                context, "Permission Error",
                "Required permissions not granted for $type. Please allow all required permissions in system settings."
            )
            if (!chatId.isNullOrBlank()) {
                UploadManager.sendTelegramMessage(chatId, "[$deviceNickname] Error: Required permissions not granted for $type.")
            }
            // Free resources if not granted
            onCommandActionComplete(commandId)
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
                    // Free resources if permission not granted
                    onCommandActionComplete(commandId)
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
                            commandId,
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
                            Log.e("CommandManager", "Audio/location overlay creation failed.")
                            onCommandActionComplete(commandId)
                            deferred.complete(Unit)
                            return@showViewOverlay
                        }
                        Handler(Looper.getMainLooper()).postDelayed({
                            OverlayHelper.removeOverlay(context, overlayView)
                            startOtherActionInvoke(context, type, duration, chatId, commandId)
                            deferred.complete(Unit)
                        }, 400)
                    }, overlaySizeDp = 64, offScreen = true)
                    deferred.await()
                    return
                }
            }
        } else {
            startCameraActionInvoke(context, type, camera, flash, quality, duration, chatId, commandId)
        }
        startOtherActionInvoke(context, type, duration, chatId, commandId)
    }

    private fun startCameraActionInvoke(
        context: Context,
        type: String,
        camera: String?,
        flash: String?,
        quality: String?,
        duration: String?,
        chatId: String?,
        commandId: Long,
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
            chatId,
            commandId // Pass commandId so service can report completion!
        )
    }

    private fun startOtherActionInvoke(
        context: Context,
        type: String,
        duration: String?,
        chatId: String?,
        commandId: Long
    ) {
        when (type) {
            "audio" -> {
                val durationInt = duration?.toIntOrNull() ?: 60
                ForegroundActionService.startAudioAction(
                    context,
                    JSONObject().apply {
                        put("duration", durationInt)
                    },
                    chatId,
                    commandId // Pass commandId so service can report completion!
                )
            }
            "location" -> {
                ForegroundActionService.startLocationAction(context, chatId, commandId)
            }
            "ring" -> {
                ForegroundActionService.startRingAction(context, JSONObject(), commandId)
            }
            "vibrate" -> {
                ForegroundActionService.startVibrateAction(context, JSONObject(), commandId)
            }
            else -> {
                NotificationHelper.showNotification(context, "Unknown Action", "Action $type is not supported.")
                Log.w("CommandManager", "Unknown action type: $type")
                if (!chatId.isNullOrBlank()) {
                    val deviceNickname = Preferences.getNickname(context) ?: "Device"
                    val errMsg = "[$deviceNickname] Error: Action $type is not supported."
                    UploadManager.sendTelegramMessage(chatId, errMsg)
                }
                onCommandActionComplete(commandId)
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

    fun getResourceGroups(type: String): Set<ResourceGroup> = when (type) {
        "photo" -> setOf(ResourceGroup.CAMERA)
        "video" -> setOf(ResourceGroup.CAMERA, ResourceGroup.MIC)
        "audio" -> setOf(ResourceGroup.MIC)
        "location" -> setOf(ResourceGroup.LOCATION)
        // For actions that don't need exclusive access, return an empty set!
        "ring", "vibrate" -> emptySet()
        else -> emptySet()
    }

    // Called by UploadManager to re-check the queue after upload completes.
    fun triggerQueueProcess() {
        Log.d("CommandManager", "triggerQueueProcess called; event-driven queue check.")
        queueScope.launch {
            processQueueOnce()
        }
    }
}

@Entity(tableName = "command_queue")
data class CommandEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val camera: String? = null,
    val flash: String? = null,
    val quality: String? = null,
    val duration: String? = null,
    val chatId: String? = null,
    val enqueueTime: Long = System.currentTimeMillis(),
    val status: String = "pending"
) {
    fun toQueuedCommand() = QueuedCommand(type, camera, flash, quality, duration, chatId)
    companion object {
        fun fromQueuedCommand(cmd: QueuedCommand): CommandEntity =
            CommandEntity(
                type = cmd.type,
                camera = cmd.camera,
                flash = cmd.flash,
                quality = cmd.quality,
                duration = cmd.duration,
                chatId = cmd.chatId
            )
    }
}

@Dao
interface CommandQueueDao {
    @Query("SELECT * FROM command_queue WHERE status='pending' ORDER BY enqueueTime ASC")
    suspend fun getAllPending(): List<CommandEntity>
    @Query("SELECT * FROM command_queue WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): CommandEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(cmd: CommandEntity): Long
    @Query("UPDATE command_queue SET status=:status WHERE id=:id")
    suspend fun updateStatus(id: Long, status: String)
    @Query("DELETE FROM command_queue")
    suspend fun clearAll()
}

@Database(entities = [CommandEntity::class], version = 1)
abstract class CommandQueueDatabase : RoomDatabase() {
    abstract fun commandQueueDao(): CommandQueueDao
    companion object {
        @Volatile private var INSTANCE: CommandQueueDatabase? = null
        fun getInstance(context: Context): CommandQueueDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CommandQueueDatabase::class.java,
                    "command_queue_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

data class QueuedCommand(
    val type: String,
    val camera: String? = null,
    val flash: String? = null,
    val quality: String? = null,
    val duration: String? = null,
    val chatId: String? = null
)