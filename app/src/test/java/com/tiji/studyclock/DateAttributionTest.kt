package com.tiji.studyclock

import com.tiji.studyclock.domain.model.Category
import com.tiji.studyclock.domain.model.DateAttribution
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionSource
import com.tiji.studyclock.domain.stats.StatsEngine
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class DateAttributionTest {
    private val day = LocalDate.of(2026, 10, 28)
    private val category = listOf(Category(1, 1, "政治常识"))

    @Test fun endDateAttributionMovesRecordIntoWindowWithoutChangingNetDuration() {
        val start = day.minusDays(30).atTime(23, 38).toInstant(ZoneOffset.UTC).toEpochMilli()
        val session = Session(id = 1, categoryId = 1, subjectId = 1, startAt = start,
            endAt = start + 25 * 60_000, durationSec = 1500, totalCount = 16, correctCount = 10, source = SessionSource.TIMER)
        val byStart = StatsEngine.calculate(listOf(session), category, day, ZoneOffset.UTC)
        val byEnd = StatsEngine.calculate(listOf(session), category, day, ZoneOffset.UTC, DateAttribution.END)
        assertEquals(0, byStart.totalQuestions)
        assertEquals(16, byEnd.totalQuestions)
        assertEquals(1500L, byEnd.timedSeconds)
        assertEquals(day.minusDays(29), byEnd.sessionStats.single().date)
        assertEquals(23, byEnd.timeDistribution.value!!.single().hour)
    }

    @Test fun manualDateRemainsUsableWhenEndTimeIsMissing() {
        val session = Session(id = 1, categoryId = 1, subjectId = 1,
            startAt = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), totalCount = 16, correctCount = 10)
        val result = StatsEngine.calculate(listOf(session), category, day, ZoneOffset.UTC, DateAttribution.END)
        assertEquals(16, result.totalQuestions)
        assertNull(result.secondsPerQuestion.value)
        assertEquals(day, result.sessionStats.single().date)
    }
}
