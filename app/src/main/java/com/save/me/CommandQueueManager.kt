package com.save.me

import android.content.Context
import android.util.Log
import androidx.room.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

object CommandQueueManager {
    enum class ResourceGroup { CAMERA, MIC, LOCATION, NONE }

    private val queueScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val resourceMutex = Mutex()
    private val runningResources = mutableMapOf<ResourceGroup, String>() // Resource -> occupying command type (e.g. "video", "audio")
    private val runningJobIds = mutableSetOf<Long>()
    private lateinit var appContext: Context
    private lateinit var dao: CommandQueueDao
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        dao = CommandQueueDatabase.getInstance(appContext).commandQueueDao()
        initialized = true
        // No need to start a loop: everything is now handled on enqueue and job completion.
    }

    fun enqueue(command: QueuedCommand) {
        queueScope.launch {
            val needed = getResourceGroups(command.type)
            val conflicting: Pair<ResourceGroup, String>? = resourceMutex.withLock {
                needed.firstOrNull { res ->
                    runningResources.containsKey(res) && runningResources[res] != null
                }?.let { res ->
                    Pair(res, runningResources[res] ?: "")
                }
            }
            if (conflicting != null) {
                // Resource is busy, deny command and notify
                val (res, occupyingCommand) = conflicting
                val deviceNickname = Preferences.getNickname(appContext) ?: "Device"
                val msg =
                    "[$deviceNickname] Resource busy: '${res.name}' is currently used by '${occupyingCommand}'. Your '${command.type}' command was denied. Try again after the current command finishes."
                if (!command.chatId.isNullOrBlank()) {
                    UploadManager.init(appContext)
                    UploadManager.sendTelegramMessage(command.chatId, msg)
                }
                Log.w("CommandQueueManager", msg)
                return@launch // Do not insert into queue
            }

            // No conflicts, lock resources and start command immediately
            val entity = CommandEntity.fromQueuedCommand(command)
            val id = dao.insert(entity)
            resourceMutex.withLock {
                needed.forEach { runningResources[it] = command.type }
                runningJobIds.add(id)
            }
            queueScope.launch {
                try {
                    dao.updateStatus(id, "running")
                    withContext(Dispatchers.IO) {
                        ActionHandlers.executeCommand(appContext, command)
                    }
                    dao.updateStatus(id, "done")
                } catch (e: Exception) {
                    dao.updateStatus(id, "failed")
                    Log.e("CommandQueueManager", "Command failed: ${command.type}", e)
                } finally {
                    resourceMutex.withLock {
                        needed.forEach { runningResources.remove(it) }
                        runningJobIds.remove(id)
                    }
                }
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

    fun getResourceGroups(type: String): Set<ResourceGroup> = when (type) {
        "photo" -> setOf(ResourceGroup.CAMERA)
        "video" -> setOf(ResourceGroup.CAMERA, ResourceGroup.MIC)
        "audio" -> setOf(ResourceGroup.MIC)
        "location" -> setOf(ResourceGroup.LOCATION)
        "ring", "vibrate" -> setOf(ResourceGroup.NONE)
        else -> setOf(ResourceGroup.NONE)
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

    @Query("SELECT * FROM command_queue WHERE status='pending' OR status='running' ORDER BY enqueueTime ASC")
    suspend fun getAllPendingOrRunning(): List<CommandEntity>

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