package com.tiji.studyclock

import com.tiji.studyclock.domain.model.DateAttribution
import com.tiji.studyclock.domain.model.RecordMode
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionKind
import com.tiji.studyclock.domain.stats.DayStats
import com.tiji.studyclock.domain.stats.DayTrack
import com.tiji.studyclock.domain.stats.TraceBar
import com.tiji.studyclock.domain.stats.TraceBars
import com.tiji.studyclock.domain.stats.TraceScale
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 月历痕迹条：四轨各自的长度、质量深浅与「有记录但无量」的边界。 */
class TraceBarsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
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
    )

    private fun summary(vararg rows: Session) = DayStats.summarize(rows.toList(), day, DateAttribution.START)

    private fun bar(bars: List<TraceBar>, track: DayTrack) = bars.first { it.track == track }

    @Test
    fun barsFollowTheTrackOrderUsedByTheLegend() {
        val bars = TraceBars.bars(summary(session(1)), TraceScale.EMPTY)
        assertEquals(listOf(DayTrack.OBJECTIVE, DayTrack.SCORED, DayTrack.REDO, DayTrack.FOCUS), bars.map { it.track })
    }

    @Test
    fun volumeBarsAreNormalisedByTheirOwnPeak() {
        val scale = TraceScale(questions = 40, redoQuestions = 20, focusSeconds = 3600)
        val bars = TraceBars.bars(summary(session(1, total = 20, correct = 15)), scale)
        assertEquals(0.5, bar(bars, DayTrack.OBJECTIVE).ratio, 1e-9)
        // 没有复做/专注：条长为 0，但 present 必须是 false，UI 才不会画成「练了 0」。
        assertEquals(0.0, bar(bars, DayTrack.REDO).ratio, 1e-9)
        assertFalse(bar(bars, DayTrack.REDO).present)
        assertEquals(0.0, bar(bars, DayTrack.FOCUS).ratio, 1e-9)
        assertFalse(bar(bars, DayTrack.FOCUS).present)
    }

    @Test
    fun ratioNeverExceedsOneEvenIfThePeakComesFromAnotherMonth() {
        val bars = TraceBars.bars(summary(session(1, total = 60, correct = 45)), TraceScale(30, 0, 0))
        assertEquals(1.0, bar(bars, DayTrack.OBJECTIVE).ratio, 1e-9)
    }

    @Test
    fun scoreBarUsesTheRateDirectlyNotItsShareOfTheMonthPeak() {
        val bars = TraceBars.bars(
            summary(session(1, mode = RecordMode.SCORE, total = 1, correct = 0, score = 640, max = 1000)),
            TraceScale(questions = 100, redoQuestions = 100, focusSeconds = 9999),
        )
        assertEquals(0.64, bar(bars, DayTrack.SCORED).ratio, 1e-9)
        assertTrue(bar(bars, DayTrack.SCORED).present)
        assertNull(bar(bars, DayTrack.SCORED).quality)
    }

    @Test
    fun objectiveBarCarriesAccuracyAsItsDepth() {
        val bars = TraceBars.bars(summary(session(1, total = 20, correct = 17)), TraceScale(20, 0, 0))
        assertEquals(0.85, bar(bars, DayTrack.OBJECTIVE).quality!!, 1e-9)
    }

    @Test
    fun redoBarUsesRedoQuestionsAndRedoRate() {
        val bars = TraceBars.bars(
            summary(
                session(1, kind = SessionKind.REDO, total = 10, correct = 6),
                session(2, mode = RecordMode.SCORE, kind = SessionKind.REDO, total = 2, correct = 0, score = 800, max = 1000),
            ),
            TraceScale(questions = 10, redoQuestions = 10, focusSeconds = 0),
        )
        val redo = bar(bars, DayTrack.REDO)
        assertTrue(redo.present)
        assertEquals(1.0, redo.ratio, 1e-9)
        assertEquals(0.6, redo.quality!!, 1e-9)
    }

    /** 计分复做只有分数、没有题量：条长为 0，但 present 为 true，朗读要说「有复做、无题量样本」。 */
    @Test
    fun scoredRedoIsPresentWithoutALength() {
        val bars = TraceBars.bars(
            summary(session(1, mode = RecordMode.SCORE, kind = SessionKind.REDO, total = 2, correct = 0, score = 820, max = 1000)),
            TraceScale(questions = 0, redoQuestions = 0, focusSeconds = 0),
        )
        val redo = bar(bars, DayTrack.REDO)
        assertTrue(redo.present)
        assertEquals(0.0, redo.ratio, 1e-9)
        assertNull(redo.quality)
    }

    @Test
    fun focusBarFollowsNetSeconds() {
        val bars = TraceBars.bars(
            summary(session(1, mode = RecordMode.FOCUS, total = 0, correct = 0, seconds = 1800)),
            TraceScale(questions = 0, redoQuestions = 0, focusSeconds = 3600),
        )
        assertEquals(0.5, bar(bars, DayTrack.FOCUS).ratio, 1e-9)
        assertEquals(0.0, bar(bars, DayTrack.OBJECTIVE).ratio, 1e-9)
        assertFalse(bar(bars, DayTrack.OBJECTIVE).present)
    }

    /** 零时长专注不算「专注过」：和 DayStats 的 focusSessions 口径保持一致。 */
    @Test
    fun zeroLengthFocusIsNotPresent() {
        val bars = TraceBars.bars(
            summary(session(1, mode = RecordMode.FOCUS, total = 0, correct = 0, seconds = 0)),
            TraceScale(questions = 0, redoQuestions = 0, focusSeconds = 60),
        )
        assertFalse(bar(bars, DayTrack.FOCUS).present)
        assertEquals(0.0, bar(bars, DayTrack.FOCUS).ratio, 1e-9)
    }

    @Test
    fun emptyDayHasFourEmptyBars() {
        val bars = TraceBars.bars(null, TraceScale(10, 10, 10))
        assertEquals(4, bars.size)
        assertTrue(bars.all { !it.present && it.ratio == 0.0 && it.quality == null })
    }

    @Test
    fun untimedManualRecordHasNoLengthButIsStillARecordDay() {
        val bars = TraceBars.bars(summary(session(1, total = 0, correct = 0, seconds = null)), TraceScale(0, 0, 0))
        assertTrue(bars.all { it.ratio == 0.0 })
        assertTrue(bars.all { !it.present })
    }

    @Test
    fun scaleTakesThePeakOfEachTrackSeparately() {
        val days = DayStats.byDay(
            listOf(
                session(1, date = LocalDate.of(2026, 11, 1), total = 30, correct = 24),
                session(2, date = LocalDate.of(2026, 11, 2), kind = SessionKind.REDO, total = 8, correct = 6),
                session(3, date = LocalDate.of(2026, 11, 3), mode = RecordMode.FOCUS, total = 0, correct = 0, seconds = 2700),
            ),
            java.time.YearMonth.of(2026, 11),
            DateAttribution.START,
        )
        val scale = TraceBars.scale(days)
        assertEquals(30, scale.questions)
        assertEquals(8, scale.redoQuestions)
        assertEquals(2700L, scale.focusSeconds)
    }

    /** 复做的题量只能进「复」条：两条轨重复计同一批题，会让人以为当天做了两倍的量。 */
    @Test
    fun redoQuestionsNeverFeedTheObjectiveBar() {
        val bars = TraceBars.bars(
            summary(session(1, kind = SessionKind.REDO, total = 12, correct = 9)),
            TraceScale(questions = 12, redoQuestions = 12, focusSeconds = 0),
        )
        assertFalse(bar(bars, DayTrack.OBJECTIVE).present)
        assertEquals(0.0, bar(bars, DayTrack.OBJECTIVE).ratio, 1e-9)
        assertNull(bar(bars, DayTrack.OBJECTIVE).quality)
        assertEquals(1.0, bar(bars, DayTrack.REDO).ratio, 1e-9)
        assertEquals(0.75, bar(bars, DayTrack.REDO).quality!!, 1e-9)
    }
}
