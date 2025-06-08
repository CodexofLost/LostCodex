package com.save.me

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.room.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

object CommandManager {
    enum class ResourceGroup { CAMERA, MIC }

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
    }

    fun isExclusiveType(type: String): Boolean =
        type == "photo" || type == "video" || type == "audio"

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
        if (isExclusiveType(type)) {
            enqueue(
                QueuedCommand(
                    type, camera, flash, quality, duration, chatId
                )
            )
        } else {
            startOtherActionInvoke(context, type, duration, chatId, System.currentTimeMillis())
        }
    }

    fun enqueue(command: QueuedCommand) {
        queueScope.launch {
            dao.insert(CommandEntity.fromQueuedCommand(command))
            processQueueOnce()
        }
    }

    private suspend fun processQueueOnce() {
        val pending = dao.getAllPending()
        if (pending.isEmpty()) return

        resourceMutex.withLock {
            for (cmd in pending) {
                if (runningJobIds.contains(cmd.id)) continue
                val neededResources = getResourceGroups(cmd.type)
                if (neededResources.any { it in runningResources }) continue

                runningResources.addAll(neededResources)
                runningJobIds.add(cmd.id)
                queueScope.launch {
                    try {
                        dao.updateStatus(cmd.id, "running")
                        withContext(Dispatchers.IO) {
                            executeCommand(appContext, cmd.toQueuedCommand(), cmd.id)
                        }
                    } catch (e: Exception) {
                        dao.updateStatus(cmd.id, "failed")
                        onCommandActionComplete(cmd.id)
                    }
                }
            }
        }
    }

    fun onCommandActionComplete(commandId: Long) {
        queueScope.launch {
            val cmd = dao.getById(commandId)
            if (cmd != null) {
                val neededResources = getResourceGroups(cmd.type)
                dao.updateStatus(commandId, "done")
                resourceMutex.withLock {
                    runningResources.removeAll(neededResources)
                    runningJobIds.remove(commandId)
                }
                processQueueOnce()
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

    suspend fun executeCommand(context: Context, command: QueuedCommand, commandId: Long) {
        val (type, camera, flash, quality, duration, chatId) = command
        val deviceNickname = Preferences.getNickname(context) ?: "Device"

        if (!checkPermissions(context, type)) {
            NotificationHelper.showNotification(
                context, "Permission Error",
                "Required permissions not granted for $type. Please allow all required permissions in system settings."
            )
            if (!chatId.isNullOrBlank()) {
                UploadManager.sendTelegramMessage(chatId, "[$deviceNickname] Error: Required permissions not granted for $type.")
            }
            onCommandActionComplete(commandId)
            return
        }
        if ((type == "photo" || type == "video") && Build.VERSION.SDK_INT >= 34) {
            if (!OverlayHelper.hasOverlayPermission(context)) {
                OverlayHelper.requestOverlayPermission(context)
                NotificationHelper.showNotification(
                    context,
                    "Permission Needed",
                    "Grant 'Display over other apps' for full background capability."
                )
                onCommandActionComplete(commandId)
                return
            }
        }
        startActionInvoke(context, type, camera, flash, quality, duration, chatId, commandId)
    }

    private fun normalizeDuration(duration: String?): Double {
        val minMinutes = 0.1
        val maxMinutes = 60.0
        val minutes = duration?.toDoubleOrNull()?.coerceIn(minMinutes, maxMinutes) ?: 1.0
        return minutes
    }

    private fun startActionInvoke(
        context: Context,
        type: String,
        camera: String?,
        flash: String?,
        quality: String?,
        duration: String?,
        chatId: String?,
        commandId: Long
    ) {
        when (type) {
            "photo", "video" -> {
                val cam = camera ?: "front"
                val flashEnabled = flash == "true"
                val qualityInt = quality?.filter { it.isDigit() }?.toIntOrNull() ?: if (type == "photo") 1080 else 480
                val durationMinutes = normalizeDuration(duration)
                ForegroundActionService.startCameraAction(
                    context,
                    type,
                    JSONObject().apply {
                        put("camera", cam)
                        put("flash", flashEnabled)
                        put("quality", qualityInt)
                        put("duration", durationMinutes)
                    },
                    chatId,
                    commandId
                )
            }
            "audio" -> {
                val durationMinutes = normalizeDuration(duration)
                ForegroundActionService.startAudioAction(
                    context,
                    JSONObject().apply {
                        put("duration", durationMinutes)
                    },
                    chatId,
                    commandId
                )
            }
            else -> {
                startOtherActionInvoke(context, type, duration, chatId, commandId)
            }
        }
    }

    private fun startOtherActionInvoke(
        context: Context,
        type: String,
        duration: String?,
        chatId: String?,
        commandId: Long
    ) {
        when (type) {
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
        else -> emptySet()
    }

    fun triggerQueueProcess() {
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