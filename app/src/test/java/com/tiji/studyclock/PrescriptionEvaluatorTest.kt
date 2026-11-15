package com.tiji.studyclock

import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.Verdict
import com.tiji.studyclock.domain.prescribe.PrescriptionEvaluator
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrescriptionEvaluatorTest {
    private val zone = ZoneId.systemDefault()
    private val base = LocalDate.of(2026, 10, 30).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val startedAt = base
    private val completedAt = base + TimeUnit.HOURS.toMillis(2)

    private fun session(
        id: Long,
        at: Long,
        total: Int = 12,
        correct: Int = 6,
        durationSec: Int = 120,
        difficulty: Int = 0,
        categoryId: Long = 1L,
    ) = Session(
        id = id,
        categoryId = categoryId,
        subjectId = 1L,
        startAt = at,
        durationSec = durationSec,
        totalCount = total,
        correctCount = correct,
        difficulty = difficulty,
    )

    private fun beforeRows(correct: Int = 5, difficulty: Int = 0): List<Session> = listOf(
        session(1, base - TimeUnit.HOURS.toMillis(3), correct = correct, difficulty = difficulty),
        session(2, base - TimeUnit.HOURS.toMillis(2), correct = correct, difficulty = difficulty),
        session(3, base - TimeUnit.HOURS.toMillis(1), correct = correct, difficulty = difficulty),
    )

    private fun afterRows(correct: Int = 7, difficulty: Int = 0): List<Session> = listOf(
        session(11, completedAt + TimeUnit.HOURS.toMillis(1), correct = correct, difficulty = difficulty),
        session(12, completedAt + TimeUnit.HOURS.toMillis(2), correct = correct, difficulty = difficulty),
        session(13, completedAt + TimeUnit.HOURS.toMillis(3), correct = correct, difficulty = difficulty),
    )

    private fun evaluate(rows: List<Session>, categoryId: Long = 1L) =
        PrescriptionEvaluator.evaluate(rows, categoryId, startedAt, completedAt)

    @Test
    fun requiresThreeSessionsOnBothSides() {
        val result = evaluate(beforeRows().take(2) + afterRows())
        assertEquals(Verdict.INSUFFICIENT, result.verdict)
        assertTrue(result.note.contains("前测 2 次"))
        assertTrue(result.note.contains("还差 1 次"))
    }

    @Test
    fun requiresThirtyTwoQuestionsOnBothSides() {
        val thin = listOf(10, 10, 10).mapIndexed { index, total ->
            session((index + 1).toLong(), base - TimeUnit.HOURS.toMillis((3 - index).toLong()), total = total)
        }
        val result = evaluate(thin + afterRows())
        assertEquals(Verdict.INSUFFICIENT, result.verdict)
        assertTrue(result.note.contains("前测 30 题"))
        assertTrue(result.note.contains("还差 2 题"))
    }

    @Test
    fun improvementOfFivePointsIsEffective() {
        val result = evaluate(beforeRows(correct = 5) + afterRows(correct = 8))
        assertEquals(Verdict.EFFECTIVE, result.verdict)
        assertEquals(5.0 / 12, result.beforeAccuracy!!, 1e-9)
        assertEquals(8.0 / 12, result.afterAccuracy!!, 1e-9)
        assertEquals(listOf(1L, 2L, 3L), result.beforeSessionIds)
        assertEquals(listOf(11L, 12L, 13L), result.afterSessionIds)
        assertTrue(result.note.contains("变化 +"))
    }

    @Test
    fun declineOfFivePointsIsIneffective() {
        val result = evaluate(beforeRows(correct = 8) + afterRows(correct = 5))
        assertEquals(Verdict.INEFFECTIVE, result.verdict)
    }

    @Test
    fun smallChangeStaysNeutral() {
        val result = evaluate(beforeRows(correct = 6) + afterRows(correct = 6))
        assertEquals(Verdict.NEUTRAL, result.verdict)
        assertTrue(result.note.contains("变化 0.0 pt"))
    }

    @Test
    fun executionPeriodSessionsAreExcluded() {
        val during = session(99, base + TimeUnit.MINUTES.toMillis(30), correct = 12)
        val result = evaluate(beforeRows(correct = 5) + listOf(during) + afterRows(correct = 8))
        assertEquals(Verdict.EFFECTIVE, result.verdict)
        assertEquals(listOf(11L, 12L, 13L), result.afterSessionIds)
        assertFalse(result.afterSessionIds.contains(99L))
    }

    @Test
    fun onlyFirstThreePostSessionsCount() {
        val extra = listOf(
            session(14, completedAt + TimeUnit.HOURS.toMillis(4), correct = 1),
            session(15, completedAt + TimeUnit.HOURS.toMillis(5), correct = 1),
        )
        val result = evaluate(beforeRows(correct = 5) + afterRows(correct = 8) + extra)
        assertEquals(listOf(11L, 12L, 13L), result.afterSessionIds)
    }

    @Test
    fun higherPostDifficultyAddsCalibrationNotice() {
        val result = evaluate(beforeRows(correct = 6, difficulty = 1) + afterRows(correct = 6, difficulty = 2))
        assertTrue(result.calibrated)
        assertEquals(Verdict.EFFECTIVE, result.verdict)
        assertTrue(result.note.contains("校准"))
        assertTrue(result.note.contains("原始变化 0.0 pt"))
    }

    @Test
    fun missingSelfAssessmentNeverCalibrates() {
        val result = evaluate(beforeRows(correct = 6) + afterRows(correct = 6, difficulty = 3))
        assertFalse(result.calibrated)
        assertEquals(Verdict.NEUTRAL, result.verdict)
    }

    @Test
    fun secondsPerQuestionUsesTimedSubset() {
        val before = beforeRows(correct = 6).map { it.copy(durationSec = 120) }
        val after = afterRows(correct = 6).map { it.copy(durationSec = 60) }
        val result = evaluate(before + after)
        assertEquals(10.0, result.beforeSecPerQ!!, 1e-9)
        assertEquals(5.0, result.afterSecPerQ!!, 1e-9)
    }

    @Test
    fun otherCategoryRowsDoNotLeakIntoVerification() {
        val other = listOf(
            session(21, completedAt + TimeUnit.HOURS.toMillis(1), correct = 12, categoryId = 2L),
            session(22, completedAt + TimeUnit.HOURS.toMillis(2), correct = 12, categoryId = 2L),
            session(23, completedAt + TimeUnit.HOURS.toMillis(3), correct = 12, categoryId = 2L),
        )
        val result = evaluate(beforeRows().take(2) + afterRows() + other)
        assertEquals(Verdict.INSUFFICIENT, result.verdict)
        assertTrue(result.afterSessionIds.none { it in listOf(21L, 22L, 23L) })
    }

    @Test
    fun invalidRowsAreIgnored() {
        val invalid = listOf(
            session(31, completedAt + TimeUnit.HOURS.toMillis(1), total = 0, correct = 0),
            session(32, completedAt + TimeUnit.HOURS.toMillis(2), total = 12, correct = 13),
        )
        val result = evaluate(beforeRows(correct = 5) + invalid + afterRows(correct = 8))
        assertEquals(Verdict.EFFECTIVE, result.verdict)
        assertTrue(result.afterSessionIds.none { it in listOf(31L, 32L) })
    }
}
