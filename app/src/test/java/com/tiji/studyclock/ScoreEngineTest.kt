package com.tiji.studyclock

import com.tiji.studyclock.domain.model.RecordMode
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionScopes
import com.tiji.studyclock.domain.stats.ConfidenceLevel
import com.tiji.studyclock.domain.stats.ScoreEngine
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** G 轨：加权不等于平均、满分口径混合、NULL、样本门槛、乱序与跨日。 */
class ScoreEngineTest {
    private val today = LocalDate.of(2026, 11, 15)
    private val zone = ZoneId.systemDefault()
    private fun at(day: Int): Long = today.minusDays(day.toLong()).atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000L

    private fun scored(
        id: Long,
        day: Int,
        score: Int,
        max: Int,
        categoryId: Long = 1,
        pieces: Int = 1,
    ) = Session(
        id = id, categoryId = categoryId, subjectId = 1, startAt = at(day),
        totalCount = pieces, correctCount = 0, durationSec = 600,
        mode = RecordMode.SCORE, scoreTenths = score, maxScoreTenths = max,
    )

    @Test
    fun weightedRateIsNotAverageOfRates() {
        val rows = listOf(scored(1, 4, 75, 90), scored(2, 2, 40, 100), scored(3, 1, 8, 10))
        val snapshot = ScoreEngine.calculate(rows, today, zone)
        assertEquals(123.0 / 200.0, snapshot.rate.value!!, 1e-12)
        val averageOfRates = rows.map { it.scoreTenths!!.toDouble() / it.maxScoreTenths!! }.average()
        assertFalse("加权与平均必须不同", kotlin.math.abs(snapshot.rate.value!! - averageOfRates) < 1e-9)
        assertEquals(123.0 / 30.0, snapshot.averageScore.value!!, 1e-12)
        assertEquals(3, snapshot.sessionCount)
        assertEquals(listOf(3L, 2L, 1L), snapshot.recent.map { it.id })
    }

    @Test
    fun noScoreRowsReturnsNullAndNeverZero() {
        val snapshot = ScoreEngine.calculate(emptyList<Session>(), today, zone)
        assertNull(snapshot.rate.value)
        assertNull(snapshot.averageScore.value)
        assertEquals(ConfidenceLevel.INSUFFICIENT, snapshot.rate.level)
        assertFalse(snapshot.hasData)
    }

    @Test
    fun invalidScoreRowsAreDropped() {
        val rows = listOf(
            scored(1, 3, 75, 90),
            scored(2, 2, 120, 100),
            scored(3, 1, 0, 0),
        )
        val snapshot = ScoreEngine.calculate(rows, today, zone)
        assertEquals(1, snapshot.sessionCount)
        assertEquals(75.0 / 90.0, snapshot.rate.value!!, 1e-12)
    }

    @Test
    fun trendAndVolatilityNeedThreeSessions() {
        val two = listOf(scored(1, 3, 75, 90), scored(2, 1, 40, 100))
        assertNull(ScoreEngine.calculate(two, today, zone).byCategory[1L]!!.trend.value)
        val three = two + scored(3, 2, 80, 100)
        val stat = ScoreEngine.calculate(three, today, zone).byCategory[1L]!!
        assertNotNull(stat.trend.value)
        assertNotNull(stat.volatility.value)
        assertTrue(stat.volatility.value!! > 0.0)
        assertFalse(stat.maxScoreConsistent)
    }

    @Test
    fun consistentMaxScoreAllowsTargetGap() {
        val rows = listOf(
            scored(1, 4, 70, 100), scored(2, 3, 75, 100), scored(3, 2, 80, 100),
        )
        // 7.0 / 7.5 / 8.0 分，平均 7.5；目标 8.5 分，满分口径同为 10.0 分。
        assertEquals(1.0, ScoreEngine.targetGap(8.5, 100, rows)!!, 1e-12)
        assertNull(ScoreEngine.targetGap(8.5, null, rows))
        assertNull(ScoreEngine.targetGap(null, 100, rows))
        // 口径不一致时只剩 2 条可比记录，样本不足返回 null，不猜测。
        val mixed = rows.take(2) + scored(4, 1, 8, 10)
        assertNull(ScoreEngine.targetGap(8.5, 100, mixed))
        assertTrue(ScoreEngine.calculate(rows, today, zone).byCategory[1L]!!.maxScoreConsistent)
    }

    @Test
    fun scoreRowsNeverEnterCountTrack() {
        val rows = listOf(scored(1, 1, 75, 90))
        assertTrue(SessionScopes.performance(rows).isEmpty())
        assertEquals(1, SessionScopes.activity(rows).size)
    }

    @Test
    fun shuffledInputKeepsChronologicalTrend() {
        val ordered = listOf(scored(1, 5, 90, 100), scored(2, 3, 60, 100), scored(3, 1, 30, 100))
        val a = ScoreEngine.calculate(ordered, today, zone).byCategory[1L]!!.trend.value!!
        val b = ScoreEngine.calculate(ordered.reversed(), today, zone).byCategory[1L]!!.trend.value!!
        assertEquals(a, b, 1e-12)
        assertTrue(a < 0.0)
    }
}
