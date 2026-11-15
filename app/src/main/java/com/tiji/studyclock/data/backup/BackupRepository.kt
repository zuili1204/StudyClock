package com.tiji.studyclock.data.backup

import android.content.Context
import androidx.room.withTransaction
import com.tiji.studyclock.data.db.AppDatabase
import com.tiji.studyclock.data.db.BackupDao
import com.tiji.studyclock.data.repo.SettingsRepository
import com.tiji.studyclock.domain.model.ExamType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class RestoreReport(
    val summary: BackupSummary,
    val autoBackupName: String,
    val preferencesRestored: Boolean,
)

@Singleton
class BackupRepository @Inject constructor(
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context,
) {
    private val backupDao: BackupDao = database.backupDao()
    /** 全库快照不受“当前考试”影响，导出与恢复都是六表加设置的完整视图。 */
    suspend fun snapshot(appVersion: String): BackupPayload {
        val settings = settingsRepository.settings.first()
        val currentExam = settingsRepository.currentExam.first()
        return BackupPayload(
            schemaVersion = BackupCodec.SCHEMA_VERSION,
            exportedAt = System.currentTimeMillis(),
            appVersion = appVersion,
            subjects = backupDao.subjects().map { it.toRecord() },
            categories = backupDao.categories().map { it.toRecord() },
            sessions = backupDao.sessions().map { it.toRecord() },
            prescriptions = backupDao.prescriptions().map { it.toRecord() },
            prescriptionResults = backupDao.prescriptionResults().map { it.toRecord() },
            examPlans = backupDao.examPlans().map { it.toRecord() },
            wrongBatches = backupDao.wrongBatches().map { it.toRecord() },
            reviewAttempts = backupDao.reviewAttempts().map { it.toRecord() },
            settings = settings.toRecord(),
            currentExam = currentExam.key,
        )
    }

    /**
     * 恢复顺序：校验 → 自动备份当前库 → 单事务替换六表 → 可选写回偏好。
     * 自动备份失败时直接中止，绝不在没有退路的情况下清库。
     */
    suspend fun restore(
        payload: BackupPayload,
        restorePreferences: Boolean,
        appVersion: String,
    ): RestoreReport {
        BackupValidator.requireValid(payload)
        val autoBackup = writeAutoBackup(snapshot(appVersion), AUTO_BACKUP_PREFIX)
        database.withTransaction {
            // v5：清表按外键顺序，先事件再错题袋再会话；写入按反向顺序。
            backupDao.clearReviewAttempts()
            backupDao.clearWrongBatches()
            backupDao.clearSessions()
            backupDao.clearPrescriptionResults()
            backupDao.clearPrescriptions()
            backupDao.clearCategories()
            backupDao.clearSubjects()
            backupDao.clearExamPlans()

            backupDao.insertSubjects(payload.subjects.map { it.toEntity() })
            backupDao.insertCategories(payload.categories.map { it.toEntity() })
            backupDao.insertPrescriptions(payload.prescriptions.map { it.toEntity() })
            backupDao.insertSessions(payload.sessions.map { it.toEntity() })
            backupDao.insertWrongBatches(payload.wrongBatches.map { it.toEntity() })
            backupDao.insertReviewAttempts(payload.reviewAttempts.map { it.toEntity() })
            backupDao.insertPrescriptionResults(payload.prescriptionResults.map { it.toEntity() })
            backupDao.insertExamPlans(payload.examPlans.map { it.toEntity() })
        }

        var preferencesRestored = false
        if (restorePreferences) {
            preferencesRestored = runCatching {
                payload.settings?.let { settingsRepository.save(it.toSettings()) }
                payload.currentExam?.let { settingsRepository.setCurrentExam(ExamType.fromKey(it)) }
                payload.settings != null || payload.currentExam != null
            }.getOrDefault(false)
        }
        return RestoreReport(
            summary = BackupCodec.summary(payload),
            autoBackupName = autoBackup.name,
            preferencesRestored = preferencesRestored,
        )
    }

    /**
     * 导入前的自动快照。失败时抛出异常，调用方必须中止导入——没有退路就不写库，
     * 与恢复路径共用同一目录与保留份数。
     */
    suspend fun autoBackupForImport(appVersion: String): String =
        writeAutoBackup(snapshot(appVersion), IMPORT_BACKUP_PREFIX).name

    private suspend fun writeAutoBackup(payload: BackupPayload, prefix: String): File = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, AUTO_BACKUP_DIR)
        check(directory.isDirectory || directory.mkdirs()) { "无法创建自动备份目录，操作已中止。" }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSSSSSSSS")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
        val file = File(directory, "$prefix$stamp.json")
        file.writeText(BackupCodec.encode(payload), Charsets.UTF_8)
        pruneAutoBackups()
        file
    }

    /** 只清理本应用写入的自动备份，保留最近 7 份，不碰用户选择的文件。 */
    private fun pruneAutoBackups() {
        val files = File(context.filesDir, AUTO_BACKUP_DIR).listFiles { file ->
            file.isFile && file.name.startsWith(AUTO_BACKUP_FAMILY) && file.name.endsWith(".json")
        }?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            ?: return
        files.drop(AUTO_BACKUPS_TO_KEEP).forEach { it.delete() }
    }

    private companion object {
        const val AUTO_BACKUP_DIR = "backups"
        const val AUTO_BACKUP_FAMILY = "before-"
        const val AUTO_BACKUP_PREFIX = "before-restore-"
        const val IMPORT_BACKUP_PREFIX = "before-import-"
        const val AUTO_BACKUPS_TO_KEEP = 7
    }
}
