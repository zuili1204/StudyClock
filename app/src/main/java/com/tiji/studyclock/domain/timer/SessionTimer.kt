package com.tiji.studyclock.domain.timer

/**
 * Persisted start of a live practice session. Wall clock, boot clock and boot identity are kept.
 *
 * Countdown and pause are pure state so a process death cannot lose them: [pausedTotalSec]
 * accumulates finished pauses, [pauseStartedElapsed]/[pauseStartedAt] mark an ongoing one, and a
 * null [targetSec] means open-ended ("自由计时").
 */
data class TimerStart(
    val subjectId: Long,
    val categoryId: Long,
    val startAt: Long,
    val startElapsed: Long,
    val bootCount: Int = -1,
    val targetSec: Int? = null,
    val pausedTotalSec: Int = 0,
    val pauseCount: Int = 0,
    val pauseStartedAt: Long? = null,
    val pauseStartedElapsed: Long? = null,
    /** v0.11：专注计时只记时长；结算页据此隐藏题量/得分输入。 */
    val focus: Boolean = false,
) {
    val isPaused: Boolean get() = pauseStartedElapsed != null || pauseStartedAt != null
    val isCountdown: Boolean get() = targetSec != null
}

data class FinishedTimer(
    val subjectId: Long,
    val categoryId: Long,
    val startAt: Long,
    val endAt: Long,
    val durationSec: Int,
    val pausedSec: Int = 0,
    val pauseCount: Int = 0,
    val targetSec: Int? = null,
    /** v0.11：专注结果只保存时长，不进入题量/得分表单。 */
    val focus: Boolean = false,
) {
    val isLongRunning: Boolean get() = durationSec >= LONG_RUNNING_SECONDS

    companion object {
        const val LONG_RUNNING_SECONDS = 4 * 60 * 60
    }
}

/**
 * Pure timer math. The boot clock is authoritative while the device stays up. After a reboot its
 * counter restarts, so only the wall clock can span the interruption; the stored boot count makes
 * that detectable even when the new uptime happens to exceed the old start offset.
 *
 * A pause subtracts from the net duration but never from the wall span: the record keeps its real
 * start and end, which is what "常练时段" analysis needs.
 */
object SessionTimer {
    fun elapsedSeconds(start: TimerStart, nowAt: Long, nowElapsed: Long, currentBootCount: Int = -1): Int {
        val gross = grossMillis(start, nowAt, nowElapsed, currentBootCount)
        val paused = start.pausedTotalSec * 1000L + ongoingPauseMillis(start, nowAt, nowElapsed, currentBootCount)
        return ((gross - paused) / 1000).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    /** Remaining seconds for a countdown; null when open-ended, 0 once the target is reached. */
    fun remainingSeconds(start: TimerStart, nowAt: Long, nowElapsed: Long, currentBootCount: Int = -1): Int? {
        val target = start.targetSec ?: return null
        return (target - elapsedSeconds(start, nowAt, nowElapsed, currentBootCount)).coerceAtLeast(0)
    }

    fun pause(start: TimerStart, nowAt: Long, nowElapsed: Long): TimerStart {
        if (start.isPaused) return start
        return start.copy(
            pauseStartedAt = nowAt,
            pauseStartedElapsed = nowElapsed,
            pauseCount = start.pauseCount + 1,
        )
    }

    fun resume(start: TimerStart, nowAt: Long, nowElapsed: Long, currentBootCount: Int = -1): TimerStart {
        if (!start.isPaused) return start
        val pausedSec = ongoingPauseMillis(start, nowAt, nowElapsed, currentBootCount) / 1000
        return start.copy(
            pausedTotalSec = start.pausedTotalSec + pausedSec.toInt(),
            pauseStartedAt = null,
            pauseStartedElapsed = null,
        )
    }

    fun finish(start: TimerStart, nowAt: Long, nowElapsed: Long, currentBootCount: Int = -1): FinishedTimer = FinishedTimer(
        subjectId = start.subjectId,
        categoryId = start.categoryId,
        startAt = start.startAt,
        endAt = nowAt.coerceAtLeast(start.startAt),
        durationSec = elapsedSeconds(start, nowAt, nowElapsed, currentBootCount).coerceAtLeast(1),
        pausedSec = (start.pausedTotalSec + ongoingPauseMillis(start, nowAt, nowElapsed, currentBootCount) / 1000).toInt(),
        pauseCount = start.pauseCount,
        targetSec = start.targetSec,
        focus = start.focus,
    )

    /**
     * Settlement for a countdown that has reached its target. Net duration is capped at the target,
     * while the record keeps the real start and the wall-clock instant when the countdown actually
     * ended: a late settlement (app reopened hours later) must not inflate the practice span.
     * Returns null for open-ended timers and for countdowns that are not due yet.
     */
    fun finishAtTarget(start: TimerStart, nowAt: Long, nowElapsed: Long, currentBootCount: Int = -1): FinishedTimer? {
        val target = start.targetSec?.takeIf { it > 0 } ?: return null
        if (elapsedSeconds(start, nowAt, nowElapsed, currentBootCount) < target) return null
        return FinishedTimer(
            subjectId = start.subjectId,
            categoryId = start.categoryId,
            startAt = start.startAt,
            endAt = start.startAt + (target.toLong() + start.pausedTotalSec) * 1000L,
            durationSec = target,
            pausedSec = start.pausedTotalSec,
            pauseCount = start.pauseCount,
            targetSec = target,
            focus = start.focus,
        )
    }

    /**
     * Rewrites a timer the user confirmed as mistimed. The corrected net duration determines the
     * end instant (start + net + paused), so activity-time analysis stays consistent with intent.
     */
    fun correctDuration(finished: FinishedTimer, durationSec: Int): FinishedTimer {
        require(durationSec > 0) { "确认后的时长必须为正数秒" }
        return finished.copy(
            durationSec = durationSec,
            endAt = finished.startAt + (durationSec.toLong() + finished.pausedSec) * 1000L,
        )
    }

    private fun grossMillis(start: TimerStart, nowAt: Long, nowElapsed: Long, currentBootCount: Int): Long {
        val sameBoot = start.bootCount < 0 || currentBootCount < 0 || start.bootCount == currentBootCount
        val sinceStart = nowElapsed - start.startElapsed
        return if (sameBoot && sinceStart >= 0) sinceStart else (nowAt - start.startAt).coerceAtLeast(0)
    }

    private fun ongoingPauseMillis(start: TimerStart, nowAt: Long, nowElapsed: Long, currentBootCount: Int): Long {
        if (!start.isPaused) return 0
        val pausedElapsed = start.pauseStartedElapsed
        val pausedAt = start.pauseStartedAt
        val sameBoot = start.bootCount < 0 || currentBootCount < 0 || start.bootCount == currentBootCount
        val byMonotonic = if (pausedElapsed != null && sameBoot) nowElapsed - pausedElapsed else null
        val millis = when {
            byMonotonic != null && byMonotonic >= 0 -> byMonotonic
            pausedAt != null -> nowAt - pausedAt
            else -> 0
        }
        return millis.coerceAtLeast(0)
    }
}
