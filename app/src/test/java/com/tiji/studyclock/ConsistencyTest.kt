package com.tiji.studyclock

import com.tiji.studyclock.domain.model.DateAttribution
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.stats.Consistency
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ConsistencyTest {
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 10, 28)

    private fun session(date: LocalDate): Session {
        val start = date.atTime(LocalTime.of(9, 0)).atZone(zone).toInstant().toEpochMilli()
        return Session(
            id = 0, categoryId = 1, subjectId = 1, startAt = start, endAt = start + 600_000,
            durationSec = 600, totalCount = 10, correctCount = 8,
        )
    }

    private fun sum(vararg dates: LocalDate) = dates.map(::session)

    @Test
    fun emptyHistoryHasNoStreak() {
        val result = Consistency.summarize(emptyList(), today)
        assertEquals(0, result.current)
        assertEquals(0, result.longest)
        assertEquals(0, result.activeDays)
        assertEquals(null, result.lastActive)
    }

    @Test
    fun threeConsecutiveDaysIncludingToday() {
        val result = Consistency.summarize(sum(today, today.minusDays(1), today.minusDays(2)), today)
        assertEquals(3, result.current)
        assertEquals(3, result.longest)
        assertEquals(3, result.activeDays)
    }

    @Test
    fun missingTodayDoesNotBreakYesterdayStreak() {
        val result = Consistency.summarize(sum(today.minusDays(1), today.minusDays(2)), today)
        assertEquals(2, result.current)
        assertEquals(2, result.longest)
    }

    @Test
    fun twoDaysWithoutPracticeBreaksStreak() {
        val result = Consistency.summarize(sum(today.minusDays(2), today.minusDays(3)), today)
        assertEquals(0, result.current)
        assertEquals(2L, result.daysSinceLast(today))
        assertEquals(2, result.longest)
    }

    @Test
    fun longestRunSurvivesAnEarlierGap() {
        val dates = buildList {
            add(today.minusDays(20))
            for (offset in 10L..14L) add(today.minusDays(offset))
            add(today)
        }
        val result = Consistency.summarize(dates.map(::session), today)
        assertEquals(5, result.longest)
        assertEquals(1, result.current)
    }

    @Test
    fun activeDaysOnlyCountsTheWindow() {
        val dates = listOf(today.minusDays(40), today.minusDays(35), today, today.minusDays(1))
        val result = Consistency.summarize(dates.map(::session), today, windowDays = 30)
        assertEquals(2, result.activeDays)
        assertEquals(today, result.lastActive)
    }

    @Test
    fun endAttributionMovesCrossMidnightToNextDay() {
        val start = LocalDate.of(2026, 10, 27).atTime(23, 38).atZone(zone).toInstant().toEpochMilli()
        val session = Session(
            id = 1, categoryId = 1, subjectId = 1, startAt = start,
            endAt = LocalDate.of(2026, 10, 28).atTime(0, 3).atZone(zone).toInstant().toEpochMilli(),
            durationSec = 1_500, totalCount = 16, correctCount = 13,
        )
        val result = Consistency.summarize(listOf(session), today, DateAttribution.END)
        assertEquals(today, result.lastActive)
    }
}
