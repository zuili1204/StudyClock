package com.tiji.studyclock.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 备份 schema 5：v3/v4 缺字段可解码、v5 八表往返、新表引用完整性、超版本拒绝。 */
class BackupV5Test {

    /** 一份不含任何 v5 字段的 v4 备份文本（可空字段全部显式写出，模拟旧版 encodeDefaults=true）。 */
    private val v4Json = """
        {
          "schemaVersion": 4, "exportedAt": 1790000001000, "appVersion": "0.7.0-dev",
          "subjects": [{"id":1,"name":"行测","colorHex":"#4C6FFF","sortOrder":0,"archived":false,"examType":"CIVIL_SERVANT"}],
          "categories": [{"id":10,"subjectId":1,"name":"主旨概括","groupName":null,"sortOrder":0,"archived":false}],
          "sessions": [{"id":100,"categoryId":10,"subjectId":1,"startAt":1790000000000,"endAt":1790000600000,
            "durationSec":600,"pausedSec":0,"pauseCount":0,"totalCount":10,"correctCount":6,
            "answerSequence":null,"wrongTags":null,"difficulty":2,"energy":1,"source":"TIMER",
            "prescriptionId":null,"examType":"CIVIL_SERVANT","createdAt":1790000000000,"updatedAt":1790000000000}],
          "prescriptions": [], "prescriptionResults": [],
          "examPlans": [{"examType":"CIVIL_SERVANT","name":"国考","examDate":null,"targetScore":140}],
          "settings": null, "currentExam": "CIVIL_SERVANT"
        }
    """.trimIndent()

    @Test
    fun versionConstantsMatchCompatibilityMatrix() {
        // v0.11：备份升 6（新增 FOCUS 记录模式），最低可恢复版本仍是 3。
        assertEquals(6, BackupCodec.SCHEMA_VERSION)
        assertEquals(3, BackupCodec.MIN_SCHEMA_VERSION)
    }

    @Test
    fun v4PayloadDecodesWithV5Defaults() {
        val payload = BackupCodec.decode(v4Json)
        assertEquals(4, payload.schemaVersion)
        assertTrue(payload.wrongBatches.isEmpty())
        assertTrue(payload.reviewAttempts.isEmpty())
        val session = payload.sessions.single()
        assertEquals("COUNT", session.mode)
        assertEquals("NORMAL", session.kind)
        assertNull(session.scoreTenths)
        assertNull(session.maxScoreTenths)
        assertNull(session.batchId)
        assertEquals("COUNT", payload.categories.single().defaultMode)
        assertNull(payload.examPlans.single().targetMaxScoreTenths)
        assertTrue(BackupValidator.validate(payload).isEmpty())
    }

    @Test
    fun v5RoundTripKeepsBatchesAndAttempts() {
        val base = BackupCodec.decode(v4Json)
        val batch = WrongBatchRecord(
            id = 1, originSessionId = 100, categoryId = 10, subjectId = 1,
            examType = "CIVIL_SERVANT", wrongCount = 4, wrongTags = "增长率",
            note = "第3套", originDate = "2026-11-01", backfilled = true, createdAt = 1L,
        )
        val attempt = ReviewAttemptRecord(1, 1, 200, 4, 2, 2L)
        val redo = base.sessions.single().copy(
            id = 200, kind = "REDO", batchId = 1, totalCount = 4, correctCount = 2,
            answerSequence = null, prescriptionId = null,
        )
        val payload = base.copy(
            schemaVersion = BackupCodec.SCHEMA_VERSION,
            sessions = base.sessions + redo,
            wrongBatches = listOf(batch),
            reviewAttempts = listOf(attempt),
        )
        val restored = BackupCodec.decode(BackupCodec.encode(payload))
        assertEquals(1, restored.wrongBatches.size)
        assertEquals(1, restored.reviewAttempts.size)
        assertEquals("第3套", restored.wrongBatches.single().note)
        assertTrue(restored.wrongBatches.single().backfilled)
        assertEquals("REDO", restored.sessions.last().kind)
        assertEquals(1L, restored.sessions.last().batchId)
        assertTrue("v5 往返应通过完整性校验", BackupValidator.validate(restored).isEmpty())
        val summary = BackupCodec.summary(restored)
        assertEquals(1, summary.wrongBatches)
        assertEquals(1, summary.reviewAttempts)
    }

    @Test
    fun brokenBatchReferencesAreRejectedBeforeClearingDatabase() {
        val base = BackupCodec.decode(v4Json)
        val orphan = base.copy(
            schemaVersion = BackupCodec.SCHEMA_VERSION,
            wrongBatches = listOf(
                WrongBatchRecord(1, 999, 10, 1, "CIVIL_SERVANT", 4, null, null, "2026-11-01", createdAt = 1L),
            ),
        )
        val errors = BackupValidator.validate(orphan)
        assertTrue(errors.any { it.contains("错题袋没有对应的原始记录") })
    }

    @Test
    fun unmatchedAttemptOrInconsistentPassedIsRejected() {
        val base = BackupCodec.decode(v4Json)
        val batch = WrongBatchRecord(1, 100, 10, 1, "CIVIL_SERVANT", 4, null, null, "2026-11-01", createdAt = 1L)
        val redo = base.sessions.single().copy(
            id = 200, kind = "REDO", batchId = 1, totalCount = 4, correctCount = 2, answerSequence = null,
        )
        val payload = base.copy(
            schemaVersion = BackupCodec.SCHEMA_VERSION,
            sessions = base.sessions + redo,
            wrongBatches = listOf(batch),
            reviewAttempts = listOf(ReviewAttemptRecord(1, 1, 200, 4, 3, 2L)),
        )
        assertTrue(BackupValidator.validate(payload).any { it.contains("通过数不一致") })
        assertTrue(BackupValidator.validate(payload.copy(reviewAttempts = emptyList()))
            .any { it.contains("轮次数量不一致") })
    }

    @Test
    fun futureSchemaVersionIsRejected() {
        val future = v4Json.replace("\"schemaVersion\": 4", "\"schemaVersion\": 7")
        assertTrue(BackupValidator.validate(BackupCodec.decode(future)).any { it.contains("版本与当前应用不一致") })
    }

    @Test
    fun focusRecordSurvivesRoundTripAndRejectsPracticeFields() {
        val base = BackupCodec.decode(v4Json)
        val focus = base.sessions.single().copy(
            id = 300, mode = "FOCUS", totalCount = 0, correctCount = 0,
            answerSequence = null, wrongTags = null, scoreTenths = null, maxScoreTenths = null,
            scoreLabel = null, durationSec = 2700,
        )
        val payload = base.copy(schemaVersion = BackupCodec.SCHEMA_VERSION, sessions = listOf(focus))
        assertEquals(emptyList<String>(), BackupValidator.validate(payload))
        // 序列化再解码后模式与时长都不能丢。
        val decoded = BackupCodec.decode(BackupCodec.encode(payload))
        assertEquals("FOCUS", decoded.sessions.single().mode)
        assertEquals(2700, decoded.sessions.single().durationSec)

        // 专注不许挂题量、错题关键词、对错序列或错题袋。
        assertTrue(BackupValidator.validate(payload.copy(sessions = listOf(focus.copy(totalCount = 5, correctCount = 0))))
            .any { it.contains("专注记录带有题量或答对数") })
        assertTrue(BackupValidator.validate(payload.copy(sessions = listOf(focus.copy(wrongTags = "增长率"))))
            .any { it.contains("专注记录带有错题关键词") })
        assertTrue(BackupValidator.validate(payload.copy(sessions = listOf(focus.copy(answerSequence = "1"))))
            .any { it.contains("专注记录带有对错序列") })
        assertTrue(BackupValidator.validate(payload.copy(sessions = listOf(focus.copy(kind = "REDO"))))
            .any { it.contains("专注记录带有复做性质") })
        // 时长是专注的唯一实质内容，缺失即视为不可恢复。
        assertTrue(BackupValidator.validate(payload.copy(sessions = listOf(focus.copy(durationSec = null))))
            .any { it.contains("专注记录缺少时长") })
    }

    @Test
    fun scoreRowRequiresValidBoundsAndNoSequence() {
        val base = BackupCodec.decode(v4Json)
        val scored = base.sessions.single().copy(
            mode = "SCORE", scoreTenths = 110, maxScoreTenths = 100, correctCount = 0,
            answerSequence = null,
        )
        assertTrue(BackupValidator.validate(base.copy(sessions = listOf(scored)))
            .any { it.contains("得分或满分不合法") })
        assertFalse(BackupValidator.validate(base.copy(sessions = listOf(scored.copy(scoreTenths = 75))))
            .any { it.contains("得分或满分不合法") })
    }
}
