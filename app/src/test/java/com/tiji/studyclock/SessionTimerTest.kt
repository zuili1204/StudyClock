package com.tiji.studyclock

import com.tiji.studyclock.domain.timer.FinishedTimer
import com.tiji.studyclock.domain.timer.SessionTimer
import com.tiji.studyclock.domain.timer.TimerStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTimerTest {
    private val start = TimerStart(subjectId = 1, categoryId = 2, startAt = 1_700_000_000_000, startElapsed = 50_000)

    @Test
    fun elapsedUsesMonotonicClockWhileDeviceIsUp() {
        assertEquals(125, SessionTimer.elapsedSeconds(start, nowAt = 1_700_000_100_000, nowElapsed = 175_000))
    }

    @Test
    fun wallClockIgnoresForwardJumpWhileMonotonicClockIsValid() {
        // A manual clock change must not inflate a running timer.
        assertEquals(30, SessionTimer.elapsedSeconds(start, nowAt = start.startAt + 9_000_000, nowElapsed = 80_000))
    }

    @Test
    fun fallsBackToWallClockWhenMonotonicClockRestarted() {
        assertEquals(90, SessionTimer.elapsedSeconds(start, nowAt = start.startAt + 90_000, nowElapsed = 10))
    }

    @Test
    fun newBootUsesWallClockEvenWhenNewUptimeExceedsStoredOffset() {
        // Reboot shortened nothing: the new uptime (80s) happens to be larger than the stored
        // 50s offset, which would otherwise look like 30s of practice instead of 90s.
        val deviceRestarted = start.copy(bootCount = 5)
        assertEquals(90, SessionTimer.elapsedSeconds(deviceRestarted, nowAt = start.startAt + 90_000, nowElapsed = 80_000, currentBootCount = 6))
    }

    @Test
    fun sameBootKeepsMonotonicClock() {
        val deviceRestarted = start.copy(bootCount = 5)
        assertEquals(125, SessionTimer.elapsedSeconds(deviceRestarted, nowAt = start.startAt + 9_000_000, nowElapsed = 175_000, currentBootCount = 5))
    }

    @Test
    fun unknownBootCountKeepsMonotonicBehavior() {
        // Timers persisted before the boot identity existed carry -1; they must keep working.
        assertEquals(125, SessionTimer.elapsedSeconds(start, nowAt = start.startAt + 9_000_000, nowElapsed = 175_000, currentBootCount = 6))
    }

    @Test
    fun fallbackNeverReturnsNegativeTime() {
        assertEquals(0, SessionTimer.elapsedSeconds(start, nowAt = start.startAt - 90_000, nowElapsed = 10))
    }

    @Test
    fun finishCapturesBothEndpointsAndNetDuration() {
        val finished = SessionTimer.finish(start, nowAt = start.startAt + 90_000, nowElapsed = 140_000)
        assertEquals(start.subjectId, finished.subjectId)
        assertEquals(start.categoryId, finished.categoryId)
        assertEquals(start.startAt, finished.startAt)
        assertEquals(start.startAt + 90_000, finished.endAt)
        assertEquals(90, finished.durationSec)
    }

    @Test
    fun immediateEndStillProducesPositiveDuration() {
        val finished = SessionTimer.finish(start, nowAt = start.startAt, nowElapsed = start.startElapsed)
        assertEquals(1, finished.durationSec)
    }

    @Test
    fun endNeverPrecedesStartEvenIfWallClockMovedBackwards() {
        val finished = SessionTimer.finish(start, nowAt = start.startAt - 60_000, nowElapsed = start.startElapsed)
        assertEquals(start.startAt, finished.endAt)
    }

    @Test
    fun longRunningStartsAtFourHours() {
        assertFalse(FinishedTimer(1, 2, 0, 1, FinishedTimer.LONG_RUNNING_SECONDS - 1).isLongRunning)
        assertTrue(FinishedTimer(1, 2, 0, 1, FinishedTimer.LONG_RUNNING_SECONDS).isLongRunning)
    }

    @Test
    fun elapsedFreezesWhilePaused() {
        // 30s 已用后暂停；再过 2 分钟仍是 30s，暂停不能计入净时长。
        val paused = SessionTimer.pause(start, nowAt = start.startAt + 30_000, nowElapsed = 80_000)
        assertTrue(paused.isPaused)
        assertEquals(30, SessionTimer.elapsedSeconds(paused, nowAt = start.startAt + 150_000, nowElapsed = 200_000))
    }

    @Test
    fun resumeSubtractsTheWholePause() {
        val paused = SessionTimer.pause(start, nowAt = start.startAt + 30_000, nowElapsed = 80_000)
        val resumed = SessionTimer.resume(paused, nowAt = start.startAt + 60_000, nowElapsed = 110_000)
        assertFalse(resumed.isPaused)
        assertEquals(30, resumed.pausedTotalSec)
        assertEquals(1, resumed.pauseCount)
        // 总跨度 90 秒，其中暂停 30 秒，净 60 秒。
        assertEquals(60, SessionTimer.elapsedSeconds(resumed, nowAt = start.startAt + 90_000, nowElapsed = 140_000))
    }

    @Test
    fun finishCarriesPauseAndTarget() {
        val paused = SessionTimer.pause(start.copy(targetSec = 1_500), nowAt = start.startAt + 30_000, nowElapsed = 80_000)
        val resumed = SessionTimer.resume(paused, nowAt = start.startAt + 60_000, nowElapsed = 110_000)
        val finished = SessionTimer.finish(resumed, nowAt = start.startAt + 90_000, nowElapsed = 140_000)
        assertEquals(60, finished.durationSec)
        assertEquals(30, finished.pausedSec)
        assertEquals(1, finished.pauseCount)
        assertEquals(1_500, finished.targetSec)
    }

    @Test
    fun correctedDurationMovesEndAndKeepsPauses() {
        val finished = FinishedTimer(
            subjectId = 1,
            categoryId = 2,
            startAt = start.startAt,
            endAt = start.startAt + 9_000_000,
            durationSec = 9_000,
            pausedSec = 600,
            pauseCount = 2,
        )
        val corrected = SessionTimer.correctDuration(finished, 1_800)
        assertEquals(1_800, corrected.durationSec)
        // 结束时刻按“开始 + 修正净时长 + 暂停”回推，看板时段分析不会落到搁置的那几小时。
        assertEquals(start.startAt + 2_400_000, corrected.endAt)
        assertEquals(600, corrected.pausedSec)
        assertEquals(2, corrected.pauseCount)
        assertEquals(finished.targetSec, corrected.targetSec)
    }

    @Test
    fun countdownCountsDownAndStopsAtZero() {
        val countdown = start.copy(targetSec = 60)
        assertEquals(50, SessionTimer.remainingSeconds(countdown, nowAt = start.startAt + 10_000, nowElapsed = 60_000) ?: -1)
        assertEquals(0, SessionTimer.remainingSeconds(countdown, nowAt = start.startAt + 120_000, nowElapsed = 170_000) ?: -1)
        assertNull(SessionTimer.remainingSeconds(start, nowAt = start.startAt + 120_000, nowElapsed = 170_000))
    }

    @Test
    fun pausedCountdownDoesNotLoseTime() {
        val countdown = SessionTimer.pause(start.copy(targetSec = 60), nowAt = start.startAt + 10_000, nowElapsed = 60_000)
        // 暂停 40 秒，剩余仍是 50 秒。
        assertEquals(50, SessionTimer.remainingSeconds(countdown, nowAt = start.startAt + 50_000, nowElapsed = 100_000) ?: -1)
    }

    @Test
    fun ongoingPauseAfterRebootFallsBackToWallClock() {
        val paused = start.copy(
            bootCount = 5,
            pauseStartedAt = start.startAt + 30_000,
            pauseStartedElapsed = 80_000,
            pauseCount = 1,
        )
        // 新开机：单调钟归零，只能用墙钟算这 20 秒暂停。
        assertEquals(30, SessionTimer.elapsedSeconds(paused, nowAt = start.startAt + 50_000, nowElapsed = 10_000, currentBootCount = 6))
    }

    @Test
    fun countdownSettlesAtTheTargetInstantEvenWhenReopenedLate() {
        val countdown = start.copy(targetSec = 1_500)
        val finished = SessionTimer.finishAtTarget(
            countdown,
            nowAt = start.startAt + 2_400_000,
            nowElapsed = start.startElapsed + 2_400_000,
        )
        assertEquals(1_500, finished?.durationSec)
        assertEquals(start.startAt, finished?.startAt)
        // 复盘时刻是归零那一刻，而不是几小时后的回到前台时刻。
        assertEquals(start.startAt + 1_500_000, finished?.endAt)
        assertEquals(0, finished?.pausedSec)
    }

    @Test
    fun countdownSettlementCountsPausedSpanOutsideNetDuration() {
        val countdown = start.copy(targetSec = 1_500, pausedTotalSec = 120, pauseCount = 2)
        val finished = SessionTimer.finishAtTarget(
            countdown,
            nowAt = start.startAt + 1_620_000,
            nowElapsed = start.startElapsed + 1_620_000,
        )
        assertEquals(1_500, finished?.durationSec)
        assertEquals(1_620_000L, finished?.endAt?.minus(start.startAt))
        assertEquals(120, finished?.pausedSec)
        assertEquals(2, finished?.pauseCount)
        assertEquals(1_500, finished?.targetSec)
    }

    @Test
    fun countdownBeforeTargetDoesNotSettle() {
        val countdown = start.copy(targetSec = 1_500)
        assertNull(
            SessionTimer.finishAtTarget(
                countdown,
                nowAt = start.startAt + 1_499_000,
                nowElapsed = start.startElapsed + 1_499_000,
            ),
        )
    }

    @Test
    fun openEndedTimerNeverSettlesAutomatically() {
        assertNull(SessionTimer.finishAtTarget(start, nowAt = start.startAt + 9_000_000, nowElapsed = start.startElapsed + 9_000_000))
    }

    @Test
    fun pauseAtTheTargetInstantStillSettles() {
        val countdown = start.copy(
            targetSec = 1_500,
            pauseStartedAt = start.startAt + 1_500_000,
            pauseStartedElapsed = start.startElapsed + 1_500_000,
            pauseCount = 1,
        )
        val finished = SessionTimer.finishAtTarget(
            countdown,
            nowAt = start.startAt + 1_530_000,
            nowElapsed = start.startElapsed + 1_530_000,
        )
        assertEquals(1_500, finished?.durationSec)
        assertEquals(start.startAt + 1_500_000, finished?.endAt)
    }
}
