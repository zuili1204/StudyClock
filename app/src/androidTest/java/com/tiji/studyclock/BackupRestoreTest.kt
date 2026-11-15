package com.tiji.studyclock

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tiji.studyclock.data.backup.BackupCodec
import com.tiji.studyclock.data.backup.BackupPayload
import com.tiji.studyclock.data.backup.BackupRepository
import com.tiji.studyclock.data.backup.CategoryRecord
import com.tiji.studyclock.data.backup.ExamPlanRecord
import com.tiji.studyclock.data.backup.SessionRecord
import com.tiji.studyclock.data.backup.SettingsRecord
import com.tiji.studyclock.data.backup.SubjectRecord
import com.tiji.studyclock.data.db.AppDatabase
import com.tiji.studyclock.data.repo.SettingsRepository
import com.tiji.studyclock.domain.model.ExamType
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AppDatabase
    private lateinit var testRoot: File
    private lateinit var settingsDirectory: File
    private lateinit var settingsScope: CoroutineScope
    private lateinit var repository: BackupRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.SEED_CALLBACK)
            .allowMainThreadQueries()
            .build()
        testRoot = File(context.cacheDir, "backup-test-${System.nanoTime()}").apply { mkdirs() }
        settingsDirectory = File(testRoot, "settings")
        // 自动备份写到隔离目录，避免仪器测试清理或覆盖应用真实的 filesDir/backups。
        val isolatedContext = object : ContextWrapper(context) {
            override fun getFilesDir(): File = testRoot
        }
        settingsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = settingsScope) {
            File(settingsDirectory, "settings.preferences_pb")
        }
        repository = BackupRepository(database, SettingsRepository(store), isolatedContext)
    }

    @After
    fun tearDown() {
        database.close()
        settingsScope.cancel()
        testRoot.deleteRecursively()
    }

    private fun payload(sessionId: Long, examType: String = ExamType.CIVIL_SERVANT.key) = BackupPayload(
        schemaVersion = BackupCodec.SCHEMA_VERSION,
        exportedAt = 1_790_000_000_000,
        appVersion = "test",
        subjects = listOf(SubjectRecord(1, "测试科目", "#112233", 0, false, examType)),
        categories = listOf(CategoryRecord(10, 1, "测试类别", null, 0, false)),
        sessions = listOf(
            SessionRecord(
                id = sessionId, categoryId = 10, subjectId = 1,
                startAt = 1_000, endAt = null, durationSec = null,
                pausedSec = 0, pauseCount = 0,
                totalCount = 1, correctCount = 1, answerSequence = null,
                difficulty = 0, energy = 0, source = "MANUAL",
                prescriptionId = null, examType = examType,
                createdAt = 1_000, updatedAt = 1_000,
            ),
        ),
        prescriptions = emptyList(),
        prescriptionResults = emptyList(),
        examPlans = listOf(ExamPlanRecord(examType, "", null, null)),
        settings = SettingsRecord(1, null, false, "START", "SYSTEM"),
        currentExam = examType,
    )

    private fun autoBackupFiles(): List<File> =
        File(testRoot, "backups").listFiles { file ->
            file.isFile && file.name.startsWith("before-restore-") && file.name.endsWith(".json")
        }?.toList().orEmpty()

    @Test
    fun restoreRoundTripsIdsNullsAndAssociations() = runBlocking {
        repository.restore(payload(100), restorePreferences = false, appVersion = "test")
        val restored = repository.snapshot("test")

        assertEquals(payload(100).subjects, restored.subjects)
        assertEquals(payload(100).categories, restored.categories)
        assertEquals(payload(100).sessions, restored.sessions)
        assertEquals(payload(100).examPlans, restored.examPlans)
        assertTrue(restored.prescriptions.isEmpty())
        assertTrue(restored.prescriptionResults.isEmpty())
        val session = restored.sessions.single()
        assertEquals(100L, session.id)
        assertNull(session.endAt)
        assertNull(session.durationSec)
        assertNull(session.answerSequence)
    }

    @Test
    fun restoreReplacesTheWholeCatalogAcrossExams() = runBlocking {
        repository.restore(payload(101, ExamType.CET.key), restorePreferences = false, appVersion = "test")
        val restored = repository.snapshot("test")
        assertEquals(ExamType.CET.key, restored.subjects.single().examType)
        assertEquals(ExamType.CET.key, restored.sessions.single().examType)
    }

    @Test
    fun restoreCanWritePreferencesAndCurrentExam() = runBlocking {
        repository.restore(payload(102, ExamType.CET.key), restorePreferences = true, appVersion = "test")
        val restored = repository.snapshot("test")
        assertEquals("CET", restored.currentExam)
        assertEquals(1, restored.settings?.dailyQuestionGoal)
        assertEquals("START", restored.settings?.dateAttribution)
    }

    @Test
    fun invalidBackupNeverTouchesExistingDataOrWritesAutoBackup() = runBlocking {
        repository.restore(payload(103), restorePreferences = false, appVersion = "test")
        val before = repository.snapshot("test")
        // 成功恢复本身会写一份 before-restore 快照；这里只断言“失败的恢复不再新增备份”。
        val backupsBeforeBrokenRestore = autoBackupFiles().size
        val broken = payload(104).copy(
            sessions = listOf(payload(104).sessions.single().copy(categoryId = 99)),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.restore(broken, restorePreferences = false, appVersion = "test") }
        }

        val after = repository.snapshot("test")
        assertEquals(before.sessions, after.sessions)
        assertEquals(before.subjects, after.subjects)
        assertEquals(backupsBeforeBrokenRestore, autoBackupFiles().size)
    }

    @Test
    fun automaticBackupsKeepOnlyTheNewestSeven() = runBlocking {
        repeat(8) { index ->
            repository.restore(payload(200L + index), restorePreferences = false, appVersion = "test")
            Thread.sleep(10)
        }
        assertEquals(7, autoBackupFiles().size)
    }
}
