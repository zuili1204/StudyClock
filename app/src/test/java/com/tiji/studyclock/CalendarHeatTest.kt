package com.tiji.studyclock

import com.tiji.studyclock.domain.model.RecordMode
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionKind
import com.tiji.studyclock.domain.stats.CalendarHeat
import com.tiji.studyclock.domain.stats.DayStats
import com.tiji.studyclock.domain.stats.DayTrack
import com.tiji.studyclock.domain.stats.HeatCell
import com.tiji.studyclock.domain.stats.HeatMetric
import com.tiji.studyclock.domain.stats.MonthRhythmRules
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 月历节律：四口径分档、无样本与无记录的区别、断档口径。 */
class CalendarHeatTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 11, 15)
    private val day = LocalDate.of(2026, 11, 3)

    private fun session(
        id: Long,
        date: LocalDate = day,
        mode: RecordMode = RecordMode.COUNT,
        kind: SessionKind = SessionKind.NORMAL,
        total: Int = 10,
        correct: Int = 7,
        seconds: Int? = 600,
        score: Int? = null,
        max: Int? = null,
        prescriptionId: Long? = null,
    ) = Session(
        id = id,
        categoryId = 1,
        subjectId = 1,
        startAt = date.atTime(20, 0).atZone(zone).toInstant().toEpochMilli(),
        durationSec = seconds,
        totalCount = total,
        correctCount = correct,
        mode = mode,
        kind = kind,
        scoreTenths = score,
        maxScoreTenths = max,
        prescriptionId = prescriptionId,
    )

    private fun summary(vararg rows: Session) = DayStats.summarize(rows.toList(), day, com.tiji.studyclock.domain.model.DateAttribution.START)

    // ---- 分轨聚合 ----

    @Test
    fun daySummaryKeepsFourTracksApart() {
        val summary = summary(
            session(1, total = 16, correct = 12),
            session(2, mode = RecordMode.SCORE, total = 1, correct = 0, score = 850, max = 1000),
            session(3, kind = SessionKind.REDO, total = 10, correct = 7),
            session(4, mode = RecordMode.FOCUS, total = 0, correct = 0, seconds = 1800),
            session(5, total = 8, correct = 6, prescriptionId = 42),
        )
        assertEquals(5, summary.sessionCount)
        assertEquals(35, summary.questions)
        // 正确率只认 COUNT + NORMAL（16 + 8 = 24 题，答对 18）。
        assertEquals(2, summary.objectiveSessions)
        assertEquals(24, summary.performanceQuestions)
        assertEquals(0.75, summary.accuracy!!, 1e-9)
        // 得分轨只认 SCORE。
        assertEquals(1, summary.scoredSessions)
        assertEquals(0.85, summary.scoreRate!!, 1e-9)
        // 复做轨只认 REDO；通过率取 COUNT 复做的题量。
        assertEquals(1, summary.redoSessions)
        assertEquals(10, summary.redoQuestions)
        assertEquals(0.7, summary.redoRate!!, 1e-9)
        // 专注只贡献时长；处方执行次数独立计数。
        assertEquals(1, summary.focusSessions)
        assertEquals(1800L, summary.focusSeconds)
        assertEquals(1, summary.prescriptionSessions)
        assertTrue(summary.has(DayTrack.OBJECTIVE))
        assertTrue(summary.has(DayTrack.SCORED))
        assertTrue(summary.has(DayTrack.REDO))
        assertTrue(summary.has(DayTrack.FOCUS))
        assertEquals(1, summary.count(DayTrack.SCORED))
    }

    @Test
    fun scoreOnlyDayHasNoAccuracyInsteadOfZero() {
        val summary = summary(session(1, mode = RecordMode.SCORE, total = 1, correct = 0, score = 700, max = 1000))
        assertNull(summary.accuracy)
        assertEquals(0.7, summary.scoreRate!!, 1e-9)
        assertFalse(summary.has(DayTrack.OBJECTIVE))
        assertTrue(summary.has(DayTrack.SCORED))
    }

    /** D20：计分复做的 correctCount 是「不适用」的 0，不能拿它算通过率。 */
    @Test
    fun scoredRedoCountsAsRedoButNotAsPassRate() {
        val summary = summary(
            session(1, mode = RecordMode.SCORE, kind = SessionKind.REDO, total = 2, correct = 0, score = 820, max = 1000),
        )
        assertEquals(1, summary.redoSessions)
        assertEquals(0, summary.redoQuestions)
        assertNull(summary.redoRate)
        assertEquals(0.82, summary.scoreRate!!, 1e-9)
    }

    @Test
    fun zeroDurationFocusDoesNotLightTheFocusTrack() {
        val summary = summary(session(1, mode = RecordMode.FOCUS, total = 0, correct = 0, seconds = 0, score = null, max = null))
        assertEquals(0, summary.focusSessions)
        assertEquals(0L, summary.focusSeconds)
        assertTrue(summary.hasRecords)
    }

    @Test
    fun monthSummaryAddsUpEveryTrack() {
        val summary = DayStats.month(
            listOf(
                session(1, date = LocalDate.of(2026, 11, 1), total = 10, correct = 8),
                session(2, date = LocalDate.of(2026, 11, 2), mode = RecordMode.SCORE, total = 1, correct = 0, score = 800, max = 1000),
                session(3, date = LocalDate.of(2026, 11, 2), kind = SessionKind.REDO, total = 5, correct = 4),
                session(4, date = LocalDate.of(2026, 11, 2), mode = RecordMode.FOCUS, total = 0, correct = 0, seconds = 900),
            ),
            YearMonth.of(2026, 11),
            com.tiji.studyclock.domain.model.DateAttribution.START,
        )
        assertEquals(2, summary.activeDays)
        assertEquals(1, summary.objectiveSessions)
        assertEquals(1, summary.scoredSessions)
        assertEquals(1, summary.redoSessions)
        assertEquals(1, summary.focusSessions)
        assertEquals(0.8, summary.scoreRate!!, 1e-9)
        assertEquals(0.8, summary.redoRate!!, 1e-9)
        assertEquals(900L, summary.focusSeconds)
    }

    // ---- 四口径与分档 ----

    @Test
    fun noRecordAndNoSampleAreDifferentStates() {
        assertEquals(HeatCell.NoRecord, CalendarHeat.cell(null, HeatMetric.QUESTIONS, 100.0))
        val empty = summary()
        assertEquals(HeatCell.NoRecord, CalendarHeat.cell(empty, HeatMetric.QUESTIONS, 100.0))
        val focusOnly = summary(session(1, mode = RecordMode.FOCUS, total = 0, correct = 0, seconds = 1800))
        assertEquals(HeatCell.NoSample, CalendarHeat.cell(focusOnly, HeatMetric.QUESTIONS, 100.0))
        assertEquals(HeatCell.NoSample, CalendarHeat.cell(focusOnly, HeatMetric.ACCURACY, 100.0))
        assertEquals(HeatCell.Level(3, 30.0), CalendarHeat.cell(focusOnly, HeatMetric.MINUTES, 30.0))
    }

    @Test
    fun untimedManualDayIsNoSampleUnderMinutes() {
        val untimed = summary(session(1, total = 20, correct = 15, seconds = null))
        assertEquals(HeatCell.NoSample, CalendarHeat.cell(untimed, HeatMetric.MINUTES, 60.0))
        assertEquals(HeatCell.Level(3, 20.0), CalendarHeat.cell(untimed, HeatMetric.QUESTIONS, 20.0))
    }

    @Test
    fun volumeBandsSplitTheMonthMaximum() {
        assertEquals(0, CalendarHeat.bandOfRatio(10.0, 40.0))
        assertEquals(1, CalendarHeat.bandOfRatio(20.0, 40.0))
        assertEquals(2, CalendarHeat.bandOfRatio(30.0, 40.0))
        assertEquals(3, CalendarHeat.bandOfRatio(40.0, 40.0))
        assertEquals(0, CalendarHeat.bandOfRatio(20.0, 0.0))
    }

    @Test
    fun rateBandsUseFixedThresholds() {
        assertEquals(0, CalendarHeat.bandOfRate(0.49))
        assertEquals(1, CalendarHeat.bandOfRate(0.5))
        assertEquals(1, CalendarHeat.bandOfRate(0.69))
        assertEquals(2, CalendarHeat.bandOfRate(0.7))
        assertEquals(2, CalendarHeat.bandOfRate(0.849))
        assertEquals(3, CalendarHeat.bandOfRate(0.85))
    }

    @Test
    fun rateMetricsIgnoreTheMonthMaximum() {
        val weak = summary(session(1, total = 10, correct = 3))
        // 30% 正确率就是第 0 档，不会因为「本月最高也只有 30%」被拉满。
        assertEquals(HeatCell.Level(0, 0.3), CalendarHeat.cell(weak, HeatMetric.ACCURACY, 0.3))
    }

    @Test
    fun monthMaxSkipsZeroAndMissingSamples() {
        val days = DayStats.byDay(
            listOf(
                session(1, date = LocalDate.of(2026, 11, 1), mode = RecordMode.FOCUS, total = 0, correct = 0, seconds = 900),
                session(2, date = LocalDate.of(2026, 11, 2), total = 30, correct = 24),
                session(3, date = LocalDate.of(2026, 11, 3), total = 12, correct = 9),
            ),
            YearMonth.of(2026, 11),
            com.tiji.studyclock.domain.model.DateAttribution.START,
        )
        assertEquals(30.0, CalendarHeat.monthMax(days, HeatMetric.QUESTIONS), 1e-9)
        assertEquals(15.0, CalendarHeat.monthMax(days, HeatMetric.MINUTES), 1e-9)
        assertEquals(0.0, CalendarHeat.monthMax(days, HeatMetric.SCORE), 1e-9)
    }

    // ---- 节律 ----

    @Test
    fun rhythmCountsRunsAndGapsInsideTheElapsedWindow() {
        val active = listOf(1, 2, 3, 7, 8).map { LocalDate.of(2026, 11, it) }
        val rhythm = MonthRhythmRules.of(active, YearMonth.of(2026, 11), today)
        assertEquals(5, rhythm.trackedDays)
        assertEquals(15, rhythm.elapsedDays)
        assertEquals(3, rhythm.longestRun)
        assertEquals(1, rhythm.gapCount)
        assertEquals(3, rhythm.longestGap)
        assertEquals(5.0 / 15, rhythm.coverage, 1e-9)
        assertTrue(rhythm.hasRecords)
    }

    @Test
    fun trailingInactiveDaysAreNotAGap() {
        // 最近一次记录是 8 号，今天 15 号；结尾没学不算断档，否则每个月都会被判成断档中。
        val active = listOf(LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 8))
        val rhythm = MonthRhythmRules.of(active, YearMonth.of(2026, 11), today)
        assertEquals(1, rhythm.gapCount)
        assertEquals(1, rhythm.longestGap)
    }

    @Test
    fun pastMonthCountsWholeMonthAndIgnoresFutureMonths() {
        val past = MonthRhythmRules.of(listOf(LocalDate.of(2026, 10, 1)), YearMonth.of(2026, 10), today)
        assertEquals(31, past.elapsedDays)
        assertEquals(0, past.gapCount)
        val future = MonthRhythmRules.of(emptyList(), YearMonth.of(2026, 12), today)
        assertEquals(0, future.elapsedDays)
        assertEquals(0.0, future.coverage, 1e-9)
        assertFalse(future.hasRecords)
    }

    @Test
    fun emptyMonthHasNoRunAndNoGap() {
        val rhythm = MonthRhythmRules.of(emptyList(), YearMonth.of(2026, 11), today)
        assertEquals(0, rhythm.trackedDays)
        assertEquals(15, rhythm.elapsedDays)
        assertEquals(0, rhythm.longestRun)
        assertEquals(0, rhythm.gapCount)
    }
}
