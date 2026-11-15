package com.tiji.studyclock

import com.tiji.studyclock.domain.model.DateAttribution
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.stats.DayStats
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DayStatsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 10, 28)

    private fun session(
        id: Long,
        date: LocalDate = day,
        total: Int = 16,
        correct: Int = 12,
        seconds: Int? = 600,
    ): Session = Session(
        id = id, categoryId = 1, subjectId = 1,
        startAt = date.atTime(20, 0).atZone(zone).toInstant().toEpochMilli(),
        durationSec = seconds, totalCount = total, correctCount = correct,
    )

    @Test
    fun daySummaryCombinesCountsAndOnlyCountsPositiveDurations() {
        val summary = DayStats.summarize(
            listOf(session(1), session(2, total = 10, correct = 7, seconds = null), session(3, seconds = 0)),
            day,
            DateAttribution.START,
        )
        assertEquals(3, summary.sessionCount)
        assertEquals(42, summary.questions)
        assertEquals(31, summary.correct)
        assertEquals(31.0 / 42, summary.accuracy!!, 1e-9)
        assertEquals(600L, summary.timedSeconds)
        assertEquals(1, summary.timedSessions)
        assertTrue(summary.hasRecords)
    }

    @Test
    fun emptyDayHasNullAccuracyInsteadOfZero() {
        val summary = DayStats.summarize(emptyList(), day, DateAttribution.START)
        assertFalse(summary.hasRecords)
        assertNull(summary.accuracy)
        assertEquals(0L, summary.timedSeconds)
    }

    @Test
    fun byDayOnlyKeepsTheRequestedMonth() {
        val map = DayStats.byDay(
            listOf(session(1, day), session(2, day.plusDays(1)), session(3, LocalDate.of(2026, 9, 30))),
            YearMonth.of(2026, 10),
            DateAttribution.START,
        )
        assertEquals(setOf(day, day.plusDays(1)), map.keys)
        assertEquals(16, map.getValue(day).questions)
    }

    @Test
    fun monthSummaryCountsDistinctActiveDays() {
        val summary = DayStats.month(
            listOf(session(1, day), session(2, day), session(3, day.plusDays(2), total = 10, correct = 5)),
            YearMonth.of(2026, 10),
            DateAttribution.START,
        )
        assertEquals(2, summary.activeDays)
        assertEquals(42, summary.questions)
        assertEquals(29, summary.correct)
        assertEquals(1800L, summary.timedSeconds)
        assertEquals(3, summary.timedSessions)
        assertTrue(summary.hasRecords)
    }
}
