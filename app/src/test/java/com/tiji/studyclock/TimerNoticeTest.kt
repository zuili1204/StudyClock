package com.tiji.studyclock

import com.tiji.studyclock.domain.timer.TimerNoticeFactory
import com.tiji.studyclock.domain.timer.TimerStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerNoticeTest {
    private val start = TimerStart(subjectId = 1, categoryId = 2, startAt = 1_700_000_000_000, startElapsed = 50_000)
    private val nowAt = start.startAt + 125_000
    private val nowElapsed = 175_000L

    @Test
    fun openTimerCountsUpFromTheStoredStart() {
        val notice = TimerNoticeFactory.build(start, "资料分析", "速算", nowAt, nowElapsed)

        assertEquals("正在计时", notice.title)
        assertEquals("资料分析 · 速算", notice.text)
        assertNull(notice.subText)
        assertTrue(notice.usesChronometer)
        assertEquals(nowAt - 125_000, notice.chronometerBaseMillis)
        assertFalse(notice.countDown)
    }

    /** v0.11：专注复用同一状态机，只换标题措辞，计时基准与练习完全一致。 */
    @Test
    fun focusTimerOnlyChangesTheTitle() {
        val focus = start.copy(focus = true)
        val open = TimerNoticeFactory.build(focus, "资料分析", "速算", nowAt, nowElapsed)
        assertEquals("专注进行中", open.title)
        assertEquals("资料分析 · 速算", open.text)
        assertEquals(nowAt - 125_000, open.chronometerBaseMillis)

        val countdown = TimerNoticeFactory.build(focus.copy(targetSec = 1_500), "资料分析", "速算", nowAt, nowElapsed)
        assertEquals("专注倒计时中", countdown.title)
        assertEquals("目标 25:00", countdown.subText)

        val reached = TimerNoticeFactory.build(focus.copy(targetSec = 100), "资料分析", "速算", nowAt, nowElapsed)
        assertEquals("本次专注已到", reached.title)

        val paused = TimerNoticeFactory.build(
            focus.copy(pauseStartedAt = nowAt, pauseStartedElapsed = nowElapsed),
            "资料分析",
            "速算",
            nowAt,
            nowElapsed,
        )
        assertEquals("专注已暂停", paused.title)
    }

    @Test
    fun countdownCountsDownToTheTarget() {
        val countdown = start.copy(targetSec = 1_500)

        val notice = TimerNoticeFactory.build(countdown, "资料分析", "速算", nowAt, nowElapsed)

        assertEquals("倒计时进行中", notice.title)
        assertEquals(nowAt + 1_375_000, notice.chronometerBaseMillis)
        assertTrue(notice.countDown)
        assertEquals("目标 25:00", notice.subText)
    }

    @Test
    fun reachedCountdownShowsFixedElapsedInsteadOfNegativeTime() {
        val countdown = start.copy(targetSec = 100)

        val notice = TimerNoticeFactory.build(countdown, "资料分析", "速算", nowAt, nowElapsed)

        assertEquals("倒计时已到", notice.title)
        assertEquals("已用时 02:05 · 资料分析 · 速算", notice.text)
        assertFalse(notice.usesChronometer)
        assertFalse(notice.countDown)
    }

    @Test
    fun pausedTimerFreezesElapsedAndDropsTheChronometer() {
        val paused = start.copy(
            targetSec = 1_500,
            pauseStartedAt = nowAt,
            pauseStartedElapsed = nowElapsed,
        )

        val atPause = TimerNoticeFactory.build(paused, "资料分析", "速算", nowAt, nowElapsed)
        val later = TimerNoticeFactory.build(paused, "资料分析", "速算", nowAt + 30_000, nowElapsed + 30_000)

        assertEquals("计时已暂停", atPause.title)
        assertEquals("已用时 02:05 · 资料分析 · 速算", atPause.text)
        assertFalse(atPause.usesChronometer)
        assertEquals(atPause, later)
    }

    @Test
    fun missingNamesFallBackToNeutralLabels() {
        val notice = TimerNoticeFactory.build(start, null, " ", nowAt, nowElapsed)

        assertEquals("已选科目 · 已选类别", notice.text)
    }

    @Test
    fun formatSwitchesToHoursOnlyWhenNeeded() {
        assertEquals("00:00", TimerNoticeFactory.format(0))
        assertEquals("00:59", TimerNoticeFactory.format(59))
        assertEquals("01:00", TimerNoticeFactory.format(60))
        assertEquals("59:59", TimerNoticeFactory.format(3_599))
        assertEquals("1:00:00", TimerNoticeFactory.format(3_600))
        assertEquals("1:01:01", TimerNoticeFactory.format(3_661))
        assertEquals("00:00", TimerNoticeFactory.format(-5))
    }
}
