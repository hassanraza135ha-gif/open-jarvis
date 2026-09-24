package com.openjarvis.automation

import android.content.Context
import androidx.room.*
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

@Entity(tableName = "automations")
data class AutomationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val command: String,
    val scheduleType: String,
    val scheduleHour: Int = 0,
    val scheduleMinute: Int = 0,
    val scheduleDayOfWeek: Int = 0,
    val scheduleIntervalMs: Long = 0,
    val enabled: Boolean = true,
    val lastRun: Long? = null,
    val lastResult: String? = null,
    val runCount: Int = 0
)

fun AutomationEntity.toAutomation(): AutomationManager.Automation {
    val sched = when (scheduleType) {
        "daily" -> AutomationManager.AutomationSchedule.Daily(scheduleHour, scheduleMinute)
        "weekly" -> AutomationManager.AutomationSchedule.Weekly(scheduleDayOfWeek, scheduleHour, scheduleMinute)
        "interval" -> AutomationManager.AutomationSchedule.Interval(scheduleIntervalMs)
        "once" -> AutomationManager.AutomationSchedule.Once(scheduleIntervalMs)
        else -> AutomationManager.AutomationSchedule.Interval(scheduleIntervalMs.coerceAtLeast(60000L))
    }
    return AutomationManager.Automation(
        id = id,
        name = name,
        command = command,
        schedule = sched,
        enabled = enabled,
        lastRun = lastRun,
        lastResult = lastResult,
        runCount = runCount
    )
}

fun AutomationManager.Automation.toEntity(): AutomationEntity {
    var sType = "interval"
    var sHour = 0
    var sMin = 0
    var sDow = 0
    var sInterval = 0L

    when (val s = schedule) {
        is AutomationManager.AutomationSchedule.Daily -> {
            sType = "daily"
            sHour = s.hour
            sMin = s.minute
        }
        is AutomationManager.AutomationSchedule.Weekly -> {
            sType = "weekly"
            sDow = s.dayOfWeek
            sHour = s.hour
            sMin = s.minute
        }
        is AutomationManager.AutomationSchedule.Interval -> {
            sType = "interval"
            sInterval = s.intervalMs
        }
        is AutomationManager.AutomationSchedule.Once -> {
            sType = "once"
            sInterval = s.atMs
        }
    }

    return AutomationEntity(
        id = id,
        name = name,
        command = command,
        scheduleType = sType,
        scheduleHour = sHour,
        scheduleMinute = sMin,
        scheduleDayOfWeek = sDow,
        scheduleIntervalMs = sInterval,
        enabled = enabled,
        lastRun = lastRun,
        lastResult = lastResult,
        runCount = runCount
    )
}

@Dao
interface AutomationDao {
    @Query("SELECT * FROM automations ORDER BY name")
    suspend fun getAllEntities(): List<AutomationEntity>

    @Query("SELECT * FROM automations WHERE id = :id")
    suspend fun getEntityById(id: String): AutomationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntity(entity: AutomationEntity)

    @Update
    suspend fun updateEntity(entity: AutomationEntity)

    @Query("DELETE FROM automations WHERE id = :id")
    suspend fun delete(id: String)

    suspend fun getAll(): List<AutomationManager.Automation> =
        getAllEntities().map { it.toAutomation() }

    suspend fun getById(id: String): AutomationManager.Automation? =
        getEntityById(id)?.toAutomation()

    suspend fun insert(automation: AutomationManager.Automation) =
        insertEntity(automation.toEntity())

    suspend fun update(automation: AutomationManager.Automation) =
        updateEntity(automation.toEntity())
}

@Database(entities = [AutomationEntity::class], version = 1, exportSchema = false)
abstract class AutomationDB : RoomDatabase() {
    abstract fun automationDao(): AutomationDao

    companion object {
        @Volatile private var INSTANCE: AutomationDB? = null

        fun getInstance(context: Context): AutomationDB {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AutomationDB::class.java,
                    "automations.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

class AutomationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString("automation_id") ?: return Result.failure()

        return try {
            val db = AutomationDB.getInstance(applicationContext)
            val dao = db.automationDao()

            val automation = dao.getById(id) ?: return Result.failure()

            kotlinx.coroutines.delay(2000)

            val updated = automation.copy(
                lastRun = System.currentTimeMillis(),
                lastResult = "success",
                runCount = automation.runCount + 1
            )
            dao.update(updated)

            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }
}
