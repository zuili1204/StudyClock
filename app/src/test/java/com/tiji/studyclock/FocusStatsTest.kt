package com.tiji.studyclock

import com.tiji.studyclock.domain.model.Category
import com.tiji.studyclock.domain.model.DateAttribution
import com.tiji.studyclock.domain.model.RecordMode
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.stats.FocusStats
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1–P5 专注口径：只从 FOCUS 记录派生，不与三条表现轨相加。 */
class FocusStatsTest {
    private val today = LocalDate.of(2026, 11, 15)
    private val zone = ZoneId.systemDefault()
    private val categories = listOf(Category(1, 1, "主旨概括"), Category(2, 1, "文章写作"))

    private fun millis(day: Int): Long =
        today.minusDays(day.toLong()).atStartOfDay(zone).toInstant().toEpochMilli() + 10 * 3_600_000L

    private fun focus(
        id: Long,
        seconds: Int?,
        day: Int = 0,
        categoryId: Long = 1,
    ) = Session(
        id = id, categoryId = categoryId, subjectId = 1, startAt = millis(day),
        endAt = millis(day) + (seconds ?: 0) * 1000L, durationSec = seconds,
        totalCount = 0, correctCount = 0, mode = RecordMode.FOCUS,
    )

    private fun practice(id: Long, total: Int, seconds: Int? = 600, day: Int = 0) = Session(
        id = id, categoryId = 1, subjectId = 1, startAt = millis(day), durationSec = seconds,
        totalCount = total, correctCount = total, mode = RecordMode.COUNT,
    )

    @Test
    fun emptyHistoryYieldsNullsNotZeros() {
        val snapshot = FocusStats.calculate(emptyList(), today, zone)
        assertEquals(0L, snapshot.totalSeconds)
        assertEquals(0, snapshot.sessionCount)
        assertNull("没有样本时平均单次应为 NULL", snapshot.averageSeconds)
        assertNull("没有投入时长时占比应为 NULL", snapshot.shareOfStudyTime)
        assertEquals(0, snapshot.longestStreakDays)
        assertTrue(snapshot.sessionIds.isEmpty())
        assertTrue(snapshot.byCategory.isEmpty())
    }

    @Test
    fun onlyPracticeGivesZeroShareButNeverNullTotal() {
        val snapshot = FocusStats.calculate(listOf(practice(1, 10, 1200)), today, zone)
        assertEquals(0L, snapshot.totalSeconds)
        assertEquals(0, snapshot.sessionCount)
        assertEquals(0.0, snapshot.shareOfStudyTime!!, 1e-12)
        assertNull(snapshot.averageSeconds)
    }

    @Test
    fun singleFocusRecordHasDurationButNoAverage() {
        val snapshot = FocusStats.calculate(listOf(focus(1, 1800)), today, zone)
        assertEquals(1800L, snapshot.totalSeconds)
        assertEquals(1, snapshot.sessionCount)
        assertNull("只有 1 次不给平均值，避免用单次冒充常态", snapshot.averageSeconds)
        assertEquals(1.0, snapshot.shareOfStudyTime!!, 1e-12)
        assertEquals(listOf(1L), snapshot.sessionIds)
        assertEquals(1, snapshot.longestStreakDays)
    }

    @Test
    fun averageNeedsTwoSessionsAndIsIntegerDivision() {
        val snapshot = FocusStats.calculate(
            listOf(focus(1, 1800), focus(2, 1000, day = 1), practice(3, 20, 600)),
            today,
            zone,
        )
        assertEquals(2800L, snapshot.totalSeconds)
        assertEquals(2, snapshot.sessionCount)
        assertEquals(1400L, snapshot.averageSeconds)
        // 2800 专注 / (2800 专注 + 600 练习)
        assertEquals(2800.0 / 3400.0, snapshot.shareOfStudyTime!!, 1e-12)
    }

    @Test
    fun streakCountsConsecutiveFocusDaysOnly() {
        val rows = listOf(
            focus(1, 600, day = 0),
            focus(2, 600, day = 1),
            focus(3, 600, day = 2),
            focus(4, 600, day = 10),
        )
        val snapshot = FocusStats.calculate(rows, today, zone)
        assertEquals(3, snapshot.longestStreakDays)
        assertEquals(4, snapshot.sessionCount)
    }

    @Test
    fun windowDropsOldRecordsFromTotalsButKeepsStreakHistory() {
        val rows = listOf(focus(1, 1200, day = 40), focus(2, 600, day = 1))
        val snapshot = FocusStats.calculate(rows, today, zone)
        assertEquals(600L, snapshot.totalSeconds)
        assertEquals(1, snapshot.sessionCount)
        // 连击看全部历史：40 天前那次与昨天那次不连续，最长仍是 1。
        assertEquals(1, snapshot.longestStreakDays)
    }

    @Test
    fun futureFocusIsIgnored() {
        val snapshot = FocusStats.calculate(listOf(focus(1, 900, day = -2)), today, zone)
        assertEquals(0L, snapshot.totalSeconds)
        assertEquals(0, snapshot.sessionCount)
    }

    @Test
    fun recordsWithoutDurationAreNotFocusTime() {
        val snapshot = FocusStats.calculate(listOf(focus(1, null), focus(2, 0)), today, zone)
        assertEquals(0L, snapshot.totalSeconds)
        assertEquals(0, snapshot.sessionCount)
        assertTrue(snapshot.sessionIds.isEmpty())
    }

    @Test
    fun byCategorySortsBySecondsThenId() {
        val rows = listOf(
            focus(1, 600, day = 0, categoryId = 2),
            focus(2, 600, day = 1, categoryId = 1),
            focus(3, 1200, day = 2, categoryId = 2),
        )
        val snapshot = FocusStats.calculate(rows, today, zone)
        assertEquals(listOf(2L, 1L), snapshot.byCategory.map { it.categoryId })
        assertEquals(1800L, snapshot.byCategory.first().seconds)
        assertEquals(2, snapshot.byCategory.first().sessions)
        assertEquals(600L, snapshot.byCategory.last().seconds)
    }

    /** 跨零点的一段专注：按开始日记账时它算两天，按结束日记账时两天并成一天。 */
    @Test
    fun attributionDecidesWhichDayACrossMidnightFocusBelongsTo() {
        val crossingMidnight = focus(1, 3600, day = 1).copy(
            startAt = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + 23 * 3_600_000L,
            endAt = today.atStartOfDay(zone).toInstant().toEpochMilli() + 600_000L,
        )
        val sameDay = focus(2, 600, day = 0)
        val rows = listOf(crossingMidnight, sameDay)

        val byStart = FocusStats.calculate(rows, today, zone, DateAttribution.START)
        assertEquals(2, byStart.longestStreakDays)
        assertEquals(4200L, byStart.totalSeconds)

        val byEnd = FocusStats.calculate(rows, today, zone, DateAttribution.END)
        assertEquals(1, byEnd.longestStreakDays)
        assertEquals(4200L, byEnd.totalSeconds)
    }
}
