package com.tiji.studyclock

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tiji.studyclock.data.db.AppDatabase
import com.tiji.studyclock.data.seed.CategorySeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    /**
     * v1 没有考试类型，按设计书 §7.3.2 迁移到 v2 时计划归到 OTHER（自定义）。
     * 这里锁定该契约：记录与科目属于考公，旧计划仍是“自定义”，不会静默改归属。
     */
    @Test
    fun v1ToV5KeepsRecordsBackfillsExamTypeAndKeepsLegacyPlanAsCustom() {
        helper.createDatabase(V1_DB, 1).apply {
            insertV1Catalog()
            execSQL(
                "INSERT INTO exam_target (id, name, examDate, targetScore) VALUES (1, '国考', '2026-11-29', 140)",
            )
            execSQL(SESSION_TIMER, arrayOf<Any>(1, 1, 1, 1_000, 5_000, 3, 1, 1, 3, 2))
            execSQL(SESSION_MANUAL, arrayOf<Any>(2, 21, 2, 20_000))
            close()
        }

        val db = helper.runMigrationsAndValidate(
            V1_DB, 5, true,
            AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5,
        )

        db.assertVersion(5)
        db.assertTableGone("exam_target")
        db.assertCatalogCounts()
        db.assertSubjectIndex()
        db.assertSessionIndex()
        assertEquals(0L, db.count("prescription"))
        assertEquals(0L, db.count("prescription_result"))

        db.assertSession(
            id = 1, categoryId = 1, subjectId = 1, startAt = 1_000, endAt = 5_000,
            durationSec = 3, totalCount = 3, correctCount = 2, answerSequence = "101", source = "TIMER",
        )
        db.assertSession(
            id = 2, categoryId = 21, subjectId = 2, startAt = 20_000, endAt = null,
            durationSec = null, totalCount = 5, correctCount = 4, answerSequence = null, source = "MANUAL",
        )
        db.query(
            "SELECT examType, name, examDate, targetScore FROM exam_plan",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("OTHER", cursor.getString(0))
            assertEquals("国考", cursor.getString(1))
            assertEquals("2026-11-29", cursor.getString(2))
            assertEquals(140L, cursor.getLong(3))
            assertFalse(cursor.moveToNext())
        }
        // v5：全链迁移后 v1 老记录仍是客观题/普通，没有分数、没有袋或轮次（D18/D25）。
        db.query(
            "SELECT mode, kind, scoreTenths, maxScoreTenths, scoreLabel, batchId, wrongTags FROM session WHERE id = 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("COUNT", cursor.getString(0))
            assertEquals("NORMAL", cursor.getString(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
            assertTrue(cursor.isNull(5))
            assertNull(cursor.getString(6))
        }
        assertEquals(0L, db.count("wrong_batch"))
        assertEquals(0L, db.count("review_attempt"))
        assertEquals(
            1L,
            db.longValue("SELECT COUNT(*) FROM category WHERE id = 39 AND defaultMode = 'SCORE'"),
        )
        assertTrue(db.indexColumns("session").containsKey(listOf("mode", "kind", "startAt")))
    }

    /** v4 → v5 单步迁移：只加列与表，历史题量/答对/关键词逐字段不变，不回填历史错题袋。 */
    @Test
    fun v4ToV5AddsTablesWithoutRewritingHistory() {
        helper.createDatabase(V4_DB, 4).apply {
            insertV2Catalog()
            execSQL(SESSION_TIMER, arrayOf<Any>(1, 1, 1, 1_000, 5_000, 3, 1, 1, 3, 2))
            execSQL(
                "INSERT INTO exam_plan (examType, name, examDate, targetScore) VALUES ('CIVIL_SERVANT', '国考', NULL, 140)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(V4_DB, 5, true, AppDatabase.MIGRATION_4_5)
        db.assertVersion(5)
        db.assertSession(
            id = 1, categoryId = 1, subjectId = 1, startAt = 1_000, endAt = 5_000,
            durationSec = 3, totalCount = 3, correctCount = 2, answerSequence = "101", source = "TIMER",
        )
        assertEquals(0L, db.count("wrong_batch"))
        assertEquals(0L, db.count("review_attempt"))
        db.query("SELECT targetMaxScoreTenths FROM exam_plan").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
        db.query("SELECT defaultMode, defaultMaxScoreTenths FROM category WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("COUNT", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
        assertTrue(db.indexColumns("wrong_batch").containsKey(listOf("originSessionId")))
        assertTrue(db.indexColumns("review_attempt").containsKey(listOf("batchId", "createdAt")))
    }

    /** v2 已经能选类型；迁移必须把已选类型与计划原样带到 v3，并按科目回填记录。 */
    @Test
    fun v2ToV4KeepsTypedPlanAndBackfillsSessionExamType() {
        helper.createDatabase(V2_DB, 2).apply {
            insertV2Catalog()
            execSQL(
                "INSERT INTO exam_target (id, name, examDate, targetScore, examType) " +
                    "VALUES (1, '省考', '2027-03-20', 135, 'CIVIL_SERVANT')",
            )
            execSQL(SESSION_TIMER, arrayOf<Any>(1, 1, 1, 1_000, 5_000, 3, 1, 1, 3, 2))
            close()
        }

        val db = helper.runMigrationsAndValidate(V2_DB, 4, true, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)

        db.assertVersion(4)
        db.assertTableGone("exam_target")
        db.assertCatalogCounts()
        db.assertSession(
            id = 1, categoryId = 1, subjectId = 1, startAt = 1_000, endAt = 5_000,
            durationSec = 3, totalCount = 3, correctCount = 2, answerSequence = "101", source = "TIMER",
        )
        db.query("SELECT examType, name, examDate, targetScore FROM exam_plan").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("CIVIL_SERVANT", cursor.getString(0))
            assertEquals("省考", cursor.getString(1))
            assertEquals("2027-03-20", cursor.getString(2))
            assertEquals(135L, cursor.getLong(3))
            assertFalse(cursor.moveToNext())
        }
    }

    /** v3 的旧记录没有关键词列；迁移只加列，不改任何已有值。 */
    @Test
    fun v3ToV4AddsWrongTagsColumnWithoutTouchingOldRows() {
        helper.createDatabase(V3_DB, 3).apply {
            insertV2Catalog()
            execSQL(SESSION_TIMER, arrayOf<Any>(1, 1, 1, 1_000, 5_000, 3, 1, 1, 3, 2))
            close()
        }

        val db = helper.runMigrationsAndValidate(V3_DB, 4, true, AppDatabase.MIGRATION_3_4)

        db.assertVersion(4)
        db.assertSession(
            id = 1, categoryId = 1, subjectId = 1, startAt = 1_000, endAt = 5_000,
            durationSec = 3, totalCount = 3, correctCount = 2, answerSequence = "101", source = "TIMER",
        )
        db.query("SELECT wrongTags FROM session WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertNull(cursor.getString(0))
        }
    }

    private fun SupportSQLiteDatabase.insertV1Catalog() {
        CategorySeed.subjects.forEach { subject ->
            execSQL(
                "INSERT INTO subject (id, name, colorHex, sortOrder, archived) VALUES (?, ?, ?, ?, 0)",
                arrayOf<Any>(subject.id, subject.name, subject.colorHex, subject.sortOrder),
            )
        }
        CategorySeed.categories.forEach { category ->
            execSQL(
                "INSERT INTO category (id, subjectId, name, groupName, sortOrder, archived) VALUES (?, ?, ?, ?, ?, 0)",
                arrayOf<Any?>(category.id, category.subjectId, category.name, category.groupName, category.sortOrder),
            )
        }
    }

    private fun SupportSQLiteDatabase.insertV2Catalog() {
        CategorySeed.subjects.forEach { subject ->
            execSQL(
                "INSERT INTO subject (id, name, colorHex, sortOrder, archived) VALUES (?, ?, ?, ?, 0)",
                arrayOf<Any>(subject.id, subject.name, subject.colorHex, subject.sortOrder),
            )
        }
        CategorySeed.categories.forEach { category ->
            execSQL(
                "INSERT INTO category (id, subjectId, name, groupName, sortOrder, archived) VALUES (?, ?, ?, ?, ?, 0)",
                arrayOf<Any?>(category.id, category.subjectId, category.name, category.groupName, category.sortOrder),
            )
        }
    }

    private fun SupportSQLiteDatabase.assertVersion(expected: Int) {
        query("PRAGMA user_version").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expected.toLong(), cursor.getLong(0))
        }
    }

    private fun SupportSQLiteDatabase.assertTableGone(table: String) {
        assertEquals(0L, longValue("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?", table))
    }

    private fun SupportSQLiteDatabase.assertCatalogCounts() {
        assertEquals(24L, count("subject"))
        assertEquals(91L, count("category"))
        assertEquals(6L, longValue("SELECT COUNT(*) FROM subject WHERE examType = 'CIVIL_SERVANT'"))
        assertEquals(4L, longValue("SELECT COUNT(*) FROM subject WHERE examType = 'POSTGRAD'"))
        assertEquals(4L, longValue("SELECT COUNT(*) FROM subject WHERE examType = 'CET'"))
        assertEquals(3L, longValue("SELECT COUNT(*) FROM subject WHERE examType = 'TEACHER_CERT'"))
        assertEquals(3L, longValue("SELECT COUNT(*) FROM subject WHERE examType = 'COMPUTER'"))
        assertEquals(4L, longValue("SELECT COUNT(*) FROM subject WHERE examType = 'IELTS'"))
    }

    private fun SupportSQLiteDatabase.assertSubjectIndex() {
        val indices = indexColumns("subject")
        assertEquals(true, indices[listOf("examType", "name")])
        assertFalse(indices.containsKey(listOf("name")))
    }

    private fun SupportSQLiteDatabase.assertSessionIndex() {
        assertTrue(indexColumns("session").containsKey(listOf("examType", "startAt")))
    }

    private fun SupportSQLiteDatabase.assertSession(
        id: Long,
        categoryId: Long,
        subjectId: Long,
        startAt: Long,
        endAt: Long?,
        durationSec: Int?,
        totalCount: Int,
        correctCount: Int,
        answerSequence: String?,
        source: String,
    ) {
        query(
            "SELECT categoryId, subjectId, startAt, endAt, durationSec, totalCount, correctCount, " +
                "answerSequence, source, examType, wrongTags FROM session WHERE id = ?",
            arrayOf<Any>(id),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(categoryId, cursor.getLong(0))
            assertEquals(subjectId, cursor.getLong(1))
            assertEquals(startAt, cursor.getLong(2))
            if (endAt == null) assertTrue(cursor.isNull(3)) else assertEquals(endAt, cursor.getLong(3))
            if (durationSec == null) assertTrue(cursor.isNull(4)) else assertEquals(durationSec.toLong(), cursor.getLong(4))
            assertEquals(totalCount.toLong(), cursor.getLong(5))
            assertEquals(correctCount.toLong(), cursor.getLong(6))
            if (answerSequence == null) assertNull(cursor.getString(7)) else assertEquals(answerSequence, cursor.getString(7))
            assertEquals(source, cursor.getString(8))
            assertEquals("CIVIL_SERVANT", cursor.getString(9))
            assertNull(cursor.getString(10))
        }
    }

    private fun SupportSQLiteDatabase.indexColumns(table: String): Map<List<String>, Boolean> {
        val result = mutableMapOf<List<String>, Boolean>()
        query("PRAGMA index_list(`$table`)").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val unique = cursor.getInt(cursor.getColumnIndexOrThrow("unique")) == 1
                val columns = mutableListOf<String>()
                query("PRAGMA index_info(`$name`)").use { info ->
                    while (info.moveToNext()) columns.add(info.getString(info.getColumnIndexOrThrow("name")))
                }
                result[columns] = unique
            }
        }
        return result
    }

    private fun SupportSQLiteDatabase.count(table: String): Long = longValue("SELECT COUNT(*) FROM `$table`")

    private fun SupportSQLiteDatabase.longValue(sql: String, vararg args: Any): Long =
        query(sql, args).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private companion object {
        const val V1_DB = "migration-v1.db"
        const val V2_DB = "migration-v2.db"
        const val V3_DB = "migration-v3.db"
        const val V4_DB = "migration-v4.db"

        val SESSION_TIMER =
            "INSERT INTO session (id, categoryId, subjectId, startAt, endAt, durationSec, pausedSec, " +
                "pauseCount, totalCount, correctCount, answerSequence, difficulty, energy, source, " +
                "prescriptionId, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '101', 1, 3, 'TIMER', NULL, 6000, 6000)"

        val SESSION_MANUAL =
            "INSERT INTO session (id, categoryId, subjectId, startAt, endAt, durationSec, pausedSec, " +
                "pauseCount, totalCount, correctCount, answerSequence, difficulty, energy, source, " +
                "prescriptionId, createdAt, updatedAt) VALUES (?, ?, ?, ?, NULL, NULL, 0, 0, 5, 4, NULL, 0, 0, 'MANUAL', NULL, 20000, 20000)"
    }
}
