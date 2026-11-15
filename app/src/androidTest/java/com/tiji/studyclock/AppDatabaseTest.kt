package com.tiji.studyclock

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tiji.studyclock.data.db.AppDatabase
import com.tiji.studyclock.data.db.CategoryEntity
import com.tiji.studyclock.data.db.DatabaseConverters
import com.tiji.studyclock.data.db.SessionDao
import com.tiji.studyclock.data.db.SessionEntity
import com.tiji.studyclock.data.db.SubjectEntity
import com.tiji.studyclock.data.repo.CategoryRepository
import com.tiji.studyclock.data.repo.ExamRepository
import com.tiji.studyclock.data.repo.PrescriptionRepository
import com.tiji.studyclock.data.repo.SessionRepository
import com.tiji.studyclock.data.seed.CategorySeed
import com.tiji.studyclock.domain.model.ExamTarget
import com.tiji.studyclock.domain.model.ExamType
import com.tiji.studyclock.domain.model.PrescriptionStatus
import com.tiji.studyclock.domain.model.PrescriptionType
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionSource
import com.tiji.studyclock.domain.model.Verdict
import com.tiji.studyclock.domain.prescribe.PlannedPrescription
import java.lang.reflect.Modifier
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.SEED_CALLBACK).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun validSession() = SessionEntity(
        categoryId = 1, subjectId = 1, startAt = 1_000, endAt = 5_000,
        durationSec = 3, pausedSec = 1, pauseCount = 1,
        totalCount = 3, correctCount = 2, answerSequence = "101",
        difficulty = 1, energy = 3, source = SessionSource.TIMER,
        createdAt = 6_000, updatedAt = 6_000,
    )

    @Test
    fun schemaAndInitialSeedContainOnlySixTablesAndNoLearningData() = runTest {
        val repository = CategoryRepository(database)
        val subjects = repository.subjects.first()
        val categories = repository.categories.first()
        assertEquals(24, subjects.size)
        assertEquals(91, categories.size)
        // 考公 1–43 是 v1 就存在的稳定基线，其余考试是 v3 起的预置骨架。
        assertEquals(CategorySeed.subjects, subjects.filter { it.examType == ExamType.CIVIL_SERVANT })
        assertEquals(CategorySeed.extraSubjects.toSet(), subjects.filter { it.examType != ExamType.CIVIL_SERVANT }.toSet())
        assertEquals(
            mapOf(
                ExamType.CIVIL_SERVANT to 6, ExamType.POSTGRAD to 4, ExamType.CET to 4,
                ExamType.TEACHER_CERT to 3, ExamType.COMPUTER to 3, ExamType.IELTS to 4,
            ),
            subjects.groupingBy { it.examType }.eachCount(),
        )
        assertEquals(CategorySeed.categories, categories.filter { it.id <= 43 })
        assertEquals(CategorySeed.extraCategories, categories.filter { it.id > 43 })
        assertEquals((1L..43L).toList(), CategorySeed.categories.map { it.id })
        assertEquals(
            listOf("常识判断", "言语理解与判断", "数量关系", "判断推理", "资料分析", "申论"),
            CategorySeed.subjects.map { it.name },
        )
        assertEquals(
            mapOf(1L to 20, 2L to 9, 3L to 2, 4L to 4, 5L to 3, 6L to 5),
            CategorySeed.categories.groupingBy { it.subjectId }.eachCount(),
        )
        assertEquals(listOf(38L, 40L), CategorySeed.categories.filter { it.name == "综合分析" }.map { it.id })
        assertEquals("法律", CategorySeed.categories.single { it.id == 2L }.groupName)
        assertEquals(5, database.openHelper.writableDatabase.version)
        val tables = mutableSetOf<String>()
        database.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            while (cursor.moveToNext()) tables.add(cursor.getString(0))
        }
        tables.removeAll(setOf("room_master_table", "android_metadata", "sqlite_sequence"))
        assertEquals(
            setOf(
                "subject", "category", "session", "prescription", "prescription_result", "exam_plan",
                "wrong_batch", "review_attempt",
            ),
            tables,
        )
        for (table in listOf("session", "prescription", "prescription_result", "exam_plan", "wrong_batch", "review_attempt")) {
            assertEquals(0L, count(table))
        }
    }

    @Test
    fun reopenDoesNotReseedDeletedCategoriesOrResetUserChanges() = runTest {
        val name = "seed-reopen-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addCallback(AppDatabase.SEED_CALLBACK).build()
        var persistent = open()
        try {
            assertEquals(91, persistent.categoryDao().observeCategories().first().size)
            persistent.categoryDao().deleteCategory(43)
            persistent.categoryDao().archiveCategory(1, true)
            persistent.categoryDao().archiveSubject(1, true)
            val customId = persistent.categoryDao().insertCategory(CategoryEntity(subjectId = 1, name = "自定义"))
            assertTrue(customId > CategorySeed.extraCategories.maxOf { it.id })
            repeat(2) {
                persistent.close()
                persistent = open()
                val categories = persistent.categoryDao().observeCategories().first()
                assertEquals(91, categories.size)
                assertNull(persistent.categoryDao().getCategory(43))
                assertTrue(categories.single { it.id == 1L }.archived)
                assertEquals("自定义", categories.single { it.id == customId }.name)
                assertTrue(persistent.categoryDao().observeSubjects().first().single { it.id == 1L }.archived)
                assertEquals(24, persistent.categoryDao().observeSubjects().first().size)
                assertTrue(persistent.sessionDao().observeAll().first().isEmpty())
            }
        } finally {
            persistent.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun indicesAndScopedUniquenessAreEnforced() = runTest {
        val sessionIndices = indices("session")
        assertTrue(sessionIndices.containsKey(listOf("categoryId", "startAt")))
        assertTrue(sessionIndices.containsKey(listOf("startAt")))
        assertTrue(sessionIndices.containsKey(listOf("subjectId")))
        assertTrue(sessionIndices.containsKey(listOf("prescriptionId")))
        assertTrue(sessionIndices.containsKey(listOf("examType", "startAt")))
        assertFalse(indices("subject").containsKey(listOf("name")))
        assertEquals(true, indices("subject")[listOf("examType", "name")])
        assertEquals(true, indices("category")[listOf("subjectId", "name")])
        assertEquals(true, indices("prescription_result")[listOf("prescriptionId")])
        val dao = database.categoryDao()
        expectFailure<SQLiteConstraintException> {
            dao.insertSubject(SubjectEntity(name = "常识判断", colorHex = "#000000", sortOrder = 8))
        }
        // 同名科目在不同考试下互不冲突（四六级与雅思都有“听力”）。
        assertTrue(
            dao.insertSubject(
                SubjectEntity(name = "常识判断", colorHex = "#000000", sortOrder = 4, examType = ExamType.CET.key),
            ) > 0,
        )
        expectFailure<SQLiteConstraintException> {
            dao.insertCategory(CategoryEntity(subjectId = 1, name = "政治常识"))
        }
        dao.insertCategory(CategoryEntity(subjectId = 1, name = "测试同名"))
        dao.insertCategory(CategoryEntity(subjectId = 2, name = "测试同名"))
        expectFailure<SQLiteConstraintException> {
            dao.insertCategory(CategoryEntity(subjectId = 999, name = "无效科目"))
        }
        insertPrescription()
        insertResult(1, emptyList(), emptyList())
        expectFailure<SQLiteConstraintException> { insertResult(1, emptyList(), emptyList()) }
        expectFailure<SQLiteConstraintException> { insertResult(999, emptyList(), emptyList()) }
    }

    @Test
    fun daoRejectsInvalidInsertAndUpdateWithoutChangingTheStoredRow() = runTest {
        val dao = database.sessionDao()
        val valid = validSession()
        val id = dao.insert(valid)
        val invalid = listOf(
            valid.copy(totalCount = 0), valid.copy(totalCount = -1),
            valid.copy(correctCount = -1), valid.copy(correctCount = 4),
            valid.copy(durationSec = 0), valid.copy(durationSec = -1),
            valid.copy(pausedSec = -1), valid.copy(pauseCount = -1),
            valid.copy(difficulty = -1), valid.copy(difficulty = 4),
            valid.copy(energy = -1), valid.copy(energy = 4),
            valid.copy(endAt = 999), valid.copy(subjectId = 2),
            valid.copy(categoryId = 999), valid.copy(subjectId = 999),
            valid.copy(prescriptionId = 999),
        )
        for (input in invalid) {
            expectFailure<IllegalArgumentException> { dao.insert(input) }
            expectFailure<IllegalArgumentException> { dao.update(input.copy(id = id)) }
            assertEquals(valid.copy(id = id), dao.getById(id))
            assertEquals(1, dao.observeAll().first().size)
        }
        expectFailure<IllegalArgumentException> { dao.update(valid.copy(id = 999)) }
        expectFailure<IllegalArgumentException> { dao.update(valid) }
        expectFailure<IllegalArgumentException> { dao.insert(valid.copy(id = id)) }
    }

    @Test
    fun daoSanitizesSequencesOnInsertAndUpdateAndAllowsUnknownTiming() = runTest {
        val dao = database.sessionDao()
        for (sequence in listOf("", "10", "1010", "1x1", "111")) {
            val original = validSession().copy(answerSequence = sequence)
            val id = dao.insert(original)
            assertEquals(original.copy(id = id, answerSequence = null), dao.getById(id))
            dao.update(original.copy(id = id, answerSequence = "101"))
            assertEquals("101", dao.getById(id)?.answerSequence)
            dao.update(original.copy(id = id))
            assertNull(dao.getById(id)?.answerSequence)
        }
        for (source in SessionSource.entries) {
            val original = validSession().copy(source = source, endAt = null, durationSec = null, answerSequence = null)
            val id = dao.insert(original)
            assertEquals(original.copy(id = id), dao.getById(id))
        }
    }

    @Test
    fun repositoryCrudAndOrdinaryUndoPreserveTheDomainModelAndId() = runTest {
        val repository = SessionRepository(database)
        val original = Session(categoryId = 1, subjectId = 1, startAt = 1_000, totalCount = 3, correctCount = 2, answerSequence = "101")
        assertTrue(repository.sessions.first().isEmpty())
        val id = repository.save(original)
        assertTrue(id > 0)
        assertEquals(listOf(original.copy(id = id)), repository.sessions.first())
        val edited = original.copy(id = id, totalCount = 4, correctCount = 3, answerSequence = "1101", updatedAt = 9_000)
        assertEquals(id, repository.save(edited))
        assertEquals(listOf(edited), repository.sessions.first())
        repository.delete(edited)
        assertTrue(repository.sessions.first().isEmpty())
        expectFailure<IllegalArgumentException> { repository.save(edited) }
        assertEquals(id, repository.restore(edited))
        assertEquals(listOf(edited), repository.sessions.first())
        expectFailure<SQLiteConstraintException> { repository.restore(edited) }
        assertEquals(listOf(edited), repository.sessions.first())
    }

    @Test
    fun categoryEditInvalidatesAndDetachesPrescriptionButOrdinaryDeleteCannotDetachIt() = runTest {
        insertPrescription()
        val dao = database.sessionDao()
        val id = dao.insert(validSession().copy(prescriptionId = 1))
        // Caller-supplied null must not evade protection of the persisted association.
        val forged = Session(id = id, categoryId = 1, subjectId = 1, startAt = 1_000, totalCount = 3, correctCount = 2)
        expectFailure<IllegalArgumentException> { SessionRepository(database).delete(forged) }
        expectFailure<IllegalArgumentException> { dao.update(dao.getById(id)!!.copy(prescriptionId = null)) }
        expectFailure<IllegalArgumentException> { dao.restore(dao.getById(id)!!) }
        expectFailure<IllegalArgumentException> { dao.insert(validSession().copy(categoryId = 21, subjectId = 2, prescriptionId = 1)) }
        dao.update(dao.getById(id)!!.copy(categoryId = 21, subjectId = 2))
        val edited = dao.getById(id)!!
        assertNull(edited.prescriptionId)
        assertEquals(21L, edited.categoryId)
        assertEquals(2L, edited.subjectId)
        assertEquals("INVALIDATED", prescriptionStatus())
        database.openHelper.writableDatabase.query("SELECT invalidatedReason FROM prescription WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertFalse(it.getString(0).isNullOrBlank())
        }
        expectFailure<IllegalArgumentException> { dao.insert(validSession().copy(prescriptionId = 1)) }
        assertEquals(1, dao.delete(id))
        assertEquals(id, dao.restore(edited))
    }

    @Test
    fun failureAfterInvalidationRollsBackTheWholeCategoryEdit() = runTest {
        insertPrescription()
        val dao = database.sessionDao()
        val id = dao.insert(validSession().copy(prescriptionId = 1))
        val original = dao.getById(id)!!
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_session_update BEFORE UPDATE ON session
            BEGIN SELECT RAISE(ABORT, 'forced update failure'); END
        """.trimIndent())
        try {
            expectFailure<SQLiteConstraintException> { dao.update(original.copy(categoryId = 2)) }
            assertEquals(original, dao.getById(id))
            assertEquals("STARTED", prescriptionStatus())
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_session_update")
        }
    }

    @Test
    fun frozenResultIdsUseExactMembershipAndCannotBecomeDangling() = runTest {
        val dao = database.sessionDao()
        val ids = (1..12).map { dao.insert(validSession()) }
        assertEquals(1L, ids.first())
        assertEquals(11L, ids[10])
        insertPrescription()
        insertResult(1, listOf(ids[10]), listOf(ids[11]))
        assertEquals(1, dao.delete(ids.first())) // ID 1 is not ID 11 or 12.
        expectFailure<IllegalArgumentException> { dao.delete(ids[10]) }
        expectFailure<IllegalArgumentException> { dao.delete(ids[11]) }
        expectFailure<SQLiteConstraintException> {
            database.openHelper.writableDatabase.execSQL("DELETE FROM prescription WHERE id = 1")
        }
        dao.update(dao.getById(ids[10])!!.copy(categoryId = 2))
        assertEquals("INVALIDATED", prescriptionStatus())
        assertEquals(1L, count("prescription_result"))
        expectFailure<IllegalArgumentException> { dao.delete(ids[10]) }
    }

    @Test
    fun prescriptionLifecycleMovesForwardOnlyAndFreezesOneResult() = runTest {
        val repository = PrescriptionRepository(database)
        val id = repository.create(listOf(plan(categoryId = 1, dueAt = 100_000))).single()
        assertEquals(PrescriptionStatus.PENDING, database.prescriptionDao().getById(id)!!.status)
        assertTrue(repository.start(id, 1_000))
        // 重复开始不推迟第一次的开始时间。
        assertTrue(repository.start(id, 2_000))
        assertEquals(1_000L, database.prescriptionDao().getById(id)!!.startedAt)
        assertTrue(repository.complete(id, 3_000))
        assertEquals(PrescriptionStatus.DONE, database.prescriptionDao().getById(id)!!.status)
        assertFalse(repository.start(id, 4_000))
        assertFalse(repository.skip(id))
        assertEquals(Verdict.INSUFFICIENT, repository.evaluateAndRecord(id)!!.verdict)
        assertEquals(0L, count("prescription_result"))

        val dao = database.sessionDao()
        repeat(3) { index -> dao.insert(validSession().copy(startAt = 100L + index, totalCount = 11, correctCount = 9)) }
        repeat(3) { index -> dao.insert(validSession().copy(startAt = 5_000L + index, totalCount = 11, correctCount = 9)) }
        assertEquals(Verdict.NEUTRAL, repository.evaluateAndRecord(id)!!.verdict)
        assertEquals(1L, count("prescription_result"))
        // 结论已冻结：再算一次不会重算，也不会新增结果行。
        assertNull(repository.evaluateAndRecord(id))
        assertEquals(1L, count("prescription_result"))

        val overdue = repository.create(listOf(plan(categoryId = 2, dueAt = 500))).single()
        assertEquals(1, repository.expireOverdue(now = 1_000))
        assertEquals(PrescriptionStatus.EXPIRED, database.prescriptionDao().getById(overdue)!!.status)
        assertFalse(repository.start(overdue, 1_100))
    }

    @Test
    fun restrictForeignKeysProtectHistoryAndEmptySubjectsNeedExplicitCategoryDeletion() = runTest {
        val categoryDao = database.categoryDao()
        val sessionId = database.sessionDao().insert(validSession())
        expectFailure<SQLiteConstraintException> { categoryDao.deleteCategory(1) }
        expectFailure<SQLiteConstraintException> { categoryDao.deleteSubject(1) }
        categoryDao.archiveCategory(1, true)
        categoryDao.archiveSubject(1, true)
        assertNotNull(database.sessionDao().getById(sessionId))
        assertTrue(categoryDao.getCategory(1)!!.archived)
        val subjectId = categoryDao.insertSubject(SubjectEntity(name = "空科目", colorHex = "#000000", sortOrder = 9))
        val categoryId = categoryDao.insertCategory(CategoryEntity(subjectId = subjectId, name = "空类别"))
        expectFailure<SQLiteConstraintException> { categoryDao.deleteSubject(subjectId) }
        assertEquals(1, categoryDao.deleteCategory(categoryId))
        assertEquals(1, categoryDao.deleteSubject(subjectId))
        insertPrescription(categoryId = 2)
        expectFailure<SQLiteConstraintException> { categoryDao.deleteCategory(2) }
        database.sessionDao().insert(validSession().copy(categoryId = 2, prescriptionId = 1))
        expectFailure<SQLiteConstraintException> {
            database.openHelper.writableDatabase.execSQL("DELETE FROM prescription WHERE id = 1")
        }
        for ((table, expectedCount) in mapOf("category" to 1, "session" to 3, "prescription" to 1, "prescription_result" to 1)) {
            var count = 0
            database.openHelper.writableDatabase.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
                while (cursor.moveToNext()) {
                    assertEquals("RESTRICT", cursor.getString(cursor.getColumnIndexOrThrow("on_delete")))
                    count++
                }
            }
            assertEquals(expectedCount, count)
        }
    }

    @Test
    fun examPlansAreKeptPerType() = runTest {
        val repository = ExamRepository(database)
        assertTrue(repository.plans.first().isEmpty())
        val civil = ExamTarget(type = ExamType.CIVIL_SERVANT, name = "省考", examDate = LocalDate.of(2028, 2, 29), targetScore = 140)
        repository.save(civil)
        val cet = ExamTarget(type = ExamType.CET, name = "六级")
        repository.save(cet)
        val plans = repository.plans.first().associateBy { it.type }
        assertEquals(civil, plans[ExamType.CIVIL_SERVANT])
        assertEquals(cet, plans[ExamType.CET])
        assertEquals(2L, count("exam_plan"))
        expectFailure<IllegalArgumentException> { repository.save(civil.copy(targetScore = 0)) }
        repository.save(civil.copy(name = "国考", examDate = null, targetScore = null))
        assertEquals("国考", repository.plans.first().first { it.type == ExamType.CIVIL_SERVANT }.name)
        assertEquals(2L, count("exam_plan"))
    }

    @Test
    fun rawSessionWritesAreProtected() {
        for (name in listOf("insertRow", "updateRow", "deleteRow")) {
            val method = SessionDao::class.java.declaredMethods.single { it.name == name }
            assertTrue(Modifier.isProtected(method.modifiers))
            assertTrue(Modifier.isAbstract(method.modifiers))
        }
    }

    private fun indices(table: String): Map<List<String>, Boolean> {
        val result = mutableMapOf<List<String>, Boolean>()
        val db = database.openHelper.writableDatabase
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val unique = cursor.getInt(cursor.getColumnIndexOrThrow("unique")) == 1
                val columns = mutableListOf<String>()
                db.query("PRAGMA index_info(`$name`)").use { info ->
                    while (info.moveToNext()) columns.add(info.getString(info.getColumnIndexOrThrow("name")))
                }
                result[columns] = unique
            }
        }
        return result
    }

    private fun count(table: String): Long =
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM `$table`").use {
            check(it.moveToFirst())
            it.getLong(0)
        }

    // Fixtures deliberately use SQL: prescription generation is outside this foundation's API.
    private fun plan(categoryId: Long, dueAt: Long) = PlannedPrescription(
        categoryId = categoryId,
        type = PrescriptionType.BASELINE,
        targetCount = 10,
        targetMinutes = null,
        targetAccuracy = null,
        attribution = "test",
        reason = "test",
        dueAt = dueAt,
    )

    private fun insertPrescription(id: Long = 1, categoryId: Long = 1) {
        database.openHelper.writableDatabase.execSQL("""
            INSERT INTO prescription
            (id, categoryId, type, targetCount, targetMinutes, targetAccuracy, attribution, reason,
             status, createdAt, startedAt, completedAt, dueAt, invalidatedReason)
            VALUES (?, ?, 'BASELINE', 3, NULL, NULL, 'INSUFFICIENT_DATA', 'test', 'STARTED', 1000, 1000, NULL, 10000, NULL)
        """.trimIndent(), arrayOf<Any>(id, categoryId))
    }

    private fun insertResult(prescriptionId: Long, before: List<Long>, after: List<Long>) {
        val converters = DatabaseConverters()
        database.openHelper.writableDatabase.execSQL("""
            INSERT INTO prescription_result
            (prescriptionId, beforeAccuracy, afterAccuracy, beforeSecPerQ, afterSecPerQ,
             beforeCount, afterCount, beforeSessionIds, afterSessionIds, verdict, evaluatedAt)
            VALUES (?, NULL, NULL, NULL, NULL, ?, ?, ?, ?, 'INSUFFICIENT', 10000)
        """.trimIndent(), arrayOf<Any>(prescriptionId, before.size, after.size, converters.idsToString(before), converters.idsToString(after)))
    }

    private fun prescriptionStatus(): String =
        database.openHelper.writableDatabase.query("SELECT status FROM prescription WHERE id = 1").use {
            check(it.moveToFirst())
            it.getString(0)
        }

    private suspend inline fun <reified T : Throwable> expectFailure(noinline action: suspend () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            if (error is T) return
            throw error
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
