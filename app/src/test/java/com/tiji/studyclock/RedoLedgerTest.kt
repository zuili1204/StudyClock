package com.tiji.studyclock

import com.tiji.studyclock.domain.model.ReviewAttempt
import com.tiji.studyclock.domain.model.WrongBatch
import com.tiji.studyclock.domain.stats.BatchStatus
import com.tiji.studyclock.domain.stats.ConfidenceLevel
import com.tiji.studyclock.domain.stats.RedoLedger
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** R 轨：全部状态由事件派生，删轮立即回退，越界阻断，归档与样本门槛。 */
class RedoLedgerTest {
    private val today = LocalDate.of(2026, 11, 15)
    private val zone = ZoneId.systemDefault()
    private fun at(day: Int): Long = LocalDate.of(2026, 11, day).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun batch(id: Long = 1, wrong: Int = 10, archived: Boolean = false) = WrongBatch(
        id = id, originSessionId = 100 + id, categoryId = 7, subjectId = 2,
        wrongCount = wrong, wrongTags = "增长率|审题", originDate = "2026-11-01",
        archived = archived,
    )

    private fun attempt(id: Long, passed: Int, tried: Int, day: Int) = ReviewAttempt(
        id = id, batchId = 1, sessionId = 200 + id,
        attemptedCount = tried, passedCount = passed, createdAt = at(day),
    )

    @Test
    fun derivesRemainingStatusAndRoundsFromEvents() {
        val rows = listOf(attempt(1, 3, 5, 3), attempt(2, 4, 4, 6))
        val ledger = RedoLedger.ledger(batch(), rows, today, zone)
        assertEquals(7, ledger.passedTotal)
        assertEquals(9, ledger.attemptedTotal)
        assertEquals(3, ledger.remaining)
        assertEquals(BatchStatus.PENDING, ledger.status)
        assertEquals(0.7, ledger.digestionRate, 1e-12)
        assertEquals(0.6, ledger.firstPassRate!!, 1e-12)
        assertEquals(2, ledger.rounds)
        assertTrue(ledger.isStalled)
        assertEquals(14L, ledger.waitingDays)
    }

    @Test
    fun deletingLastRoundRollsRemainingBackImmediately() {
        val all = listOf(attempt(1, 3, 5, 3), attempt(2, 4, 4, 6))
        val before = RedoLedger.ledger(batch(), all, today, zone)
        val after = RedoLedger.ledger(batch(), all.dropLast(1), today, zone)
        assertEquals(3, before.remaining)
        assertEquals(7, after.remaining)
        assertEquals(1, after.rounds)
    }

    @Test
    fun digestedAndArchivedStatuses() {
        val all = listOf(attempt(1, 6, 6, 3), attempt(2, 4, 4, 5))
        val digested = RedoLedger.ledger(batch(), all, today, zone)
        assertEquals(0, digested.remaining)
        assertEquals(BatchStatus.DIGESTED, digested.status)
        assertEquals(2L, digested.daysToDigest)
        assertEquals(BatchStatus.ARCHIVED, RedoLedger.ledger(batch(archived = true), all, today, zone).status)
    }

    @Test
    fun remainingNeverNegative() {
        val ledger = RedoLedger.ledger(batch(wrong = 3), listOf(attempt(1, 9, 9, 4)), today, zone)
        assertEquals(0, ledger.remaining)
    }

    @Test
    fun requireValidBlocksOutOfRangeLedgers() {
        assertThrows(IllegalArgumentException::class.java) {
            RedoLedger.requireValid(batch(), listOf(attempt(1, 6, 5, 3)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RedoLedger.requireValid(batch(wrong = 5), listOf(attempt(1, 6, 6, 3)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RedoLedger.requireValid(batch(wrong = 0), emptyList())
        }
        RedoLedger.requireValid(batch(), listOf(attempt(1, 5, 5, 3), attempt(2, 5, 5, 4)))
    }

    @Test
    fun metricsCoverR1ToR5AndRespectSampleGates() {
        val batches = listOf(batch(1, 10), batch(2, 8), batch(3, 6, archived = true))
        val attempts = listOf(
            ReviewAttempt(1, 1, 200, 5, 3, at(3)),
            ReviewAttempt(2, 1, 201, 5, 5, at(5)),
            ReviewAttempt(3, 2, 202, 4, 2, at(6)),
        )
        val metrics = RedoLedger.metrics(batches, attempts, today, zone)
        // 袋1 过 8 道剩 2，袋2 过 2 道剩 6；归档袋3 不计入待复做，共 8 道。
        assertEquals(8, metrics.pendingQuestions)
        assertEquals(3, metrics.totalBatches)
        assertEquals(0, metrics.digestedBatches)
        assertEquals(3, metrics.totalRounds)
        assertEquals(2.0 / 3.0, metrics.coverage.value!!, 1e-12)
        assertEquals(10.0 / 24.0, metrics.digestionRate.value!!, 1e-12)
        // 没有已消化袋，R5 门槛未达到。
        assertNull(metrics.averageDigestDays.value)
        assertEquals(ConfidenceLevel.INSUFFICIENT, metrics.averageDigestDays.level)
        // 首轮只有 2 袋，R3 样本不足。
        assertNull(metrics.firstPassRate.value)
        assertEquals(14L, metrics.oldestPendingDays)
        assertEquals(mapOf(7L to 8), metrics.pendingByCategory)
    }

    @Test
    fun averageDigestDaysNeedsThreeDigestedBatches() {
        val batches = (1L..3L).map { batch(it, 5) }
        val attempts = batches.flatMap { b ->
            listOf(
                ReviewAttempt(b.id * 10, b.id, 300 + b.id, 5, 5, at(5)),
                ReviewAttempt(b.id * 10 + 1, b.id, 400 + b.id, 0, 0, at(6)),
            )
        }.filter { it.attemptedCount > 0 }
        val metrics = RedoLedger.metrics(batches, attempts, today, zone)
        assertEquals(4.0, metrics.averageDigestDays.value!!, 1e-12)
    }

    @Test
    fun emptyLedgerHasNoZeroPercent() {
        val metrics = RedoLedger.metrics(emptyList(), emptyList(), today, zone)
        assertEquals(0, metrics.pendingQuestions)
        assertNull(metrics.coverage.value)
        assertNull(metrics.digestionRate.value)
        assertTrue(!metrics.hasData)
    }
}
