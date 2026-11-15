package com.tiji.studyclock.domain.timer

/** What a widget button does; the provider maps these onto broadcasts or activity intents. */
enum class WidgetAction { START, PAUSE, RESUME, FINISH, OPEN_TIMER, OPEN_CAPTURE }

/**
 * Everything the home-screen widget needs, derived from the same stored timer the app uses.
 * Kept Android-free so the running/paused/finished copy and the chronometer anchor are unit-tested.
 *
 * [chronometerBaseMillis] is in the `elapsedRealtime` timebase, which is what a platform
 * `Chronometer` expects; null means the widget shows static text instead of a live clock.
 */
data class TimerWidgetState(
    val mode: Mode,
    val title: String,
    val subtitle: String,
    val staticTime: String?,
    val chronometerBaseMillis: Long?,
    val countDown: Boolean,
    val primary: WidgetAction,
    val primaryLabel: String,
    val secondary: WidgetAction?,
    val secondaryLabel: String?,
) {
    enum class Mode { IDLE, RUNNING, PAUSED, FINISHED }
}

object TimerWidgetStates {
    const val DEFAULT_MINUTES = 25

    fun build(
        active: TimerStart?,
        finished: FinishedTimer?,
        subjectName: String?,
        categoryName: String?,
        nowAt: Long,
        nowElapsed: Long,
        bootCount: Int,
        defaultMinutes: Int = DEFAULT_MINUTES,
    ): TimerWidgetState {
        if (active != null) return running(active, subjectName, categoryName, nowAt, nowElapsed, bootCount)
        if (finished != null) {
            val place = subjectName?.takeIf { it.isNotBlank() } ?: "已选科目"
            return TimerWidgetState(
                mode = TimerWidgetState.Mode.FINISHED,
                title = "计时已结束",
                subtitle = "$place · 用时 ${TimerNoticeFactory.format(finished.durationSec)}",
                staticTime = TimerNoticeFactory.format(finished.durationSec),
                chronometerBaseMillis = null,
                countDown = false,
                primary = WidgetAction.OPEN_TIMER,
                primaryLabel = "去填写",
                secondary = WidgetAction.OPEN_CAPTURE,
                secondaryLabel = "补记",
            )
        }
        return TimerWidgetState(
            mode = TimerWidgetState.Mode.IDLE,
            title = "题迹",
            subtitle = "从一次 $defaultMinutes 分钟开始",
            staticTime = null,
            chronometerBaseMillis = null,
            countDown = false,
            primary = WidgetAction.START,
            primaryLabel = "开始 $defaultMinutes 分钟",
            secondary = WidgetAction.OPEN_CAPTURE,
            secondaryLabel = "补记",
        )
    }

    private fun running(
        start: TimerStart,
        subjectName: String?,
        categoryName: String?,
        nowAt: Long,
        nowElapsed: Long,
        bootCount: Int,
    ): TimerWidgetState {
        val subject = subjectName?.takeIf { it.isNotBlank() } ?: "已选科目"
        val category = categoryName?.takeIf { it.isNotBlank() } ?: "已选类别"
        // v0.11：专注与练习共用同一状态机，小组件只在副标题上点明「专注」。
        val focusTag = if (start.focus) " · 专注" else ""
        val elapsed = SessionTimer.elapsedSeconds(start, nowAt, nowElapsed, bootCount)
        if (start.isPaused) {
            return TimerWidgetState(
                mode = TimerWidgetState.Mode.PAUSED,
                title = category,
                subtitle = "$subject$focusTag · 已暂停",
                staticTime = TimerNoticeFactory.format(elapsed),
                chronometerBaseMillis = null,
                countDown = false,
                primary = WidgetAction.RESUME,
                primaryLabel = "继续",
                secondary = WidgetAction.FINISH,
                secondaryLabel = "结束",
            )
        }
        val target = start.targetSec
        if (target != null) {
            val remaining = (target - elapsed).coerceAtLeast(0)
            return TimerWidgetState(
                mode = TimerWidgetState.Mode.RUNNING,
                title = category,
                subtitle = "$subject$focusTag · 目标 ${TimerNoticeFactory.format(target)}",
                staticTime = null,
                chronometerBaseMillis = nowElapsed + remaining * 1000L,
                countDown = true,
                primary = WidgetAction.PAUSE,
                primaryLabel = "暂停",
                secondary = WidgetAction.FINISH,
                secondaryLabel = "结束",
            )
        }
        return TimerWidgetState(
            mode = TimerWidgetState.Mode.RUNNING,
            title = category,
            subtitle = if (start.focus) "$subject · 专注" else "$subject · 自由计时",
            staticTime = null,
            chronometerBaseMillis = nowElapsed - elapsed * 1000L,
            countDown = false,
            primary = WidgetAction.PAUSE,
            primaryLabel = "暂停",
            secondary = WidgetAction.FINISH,
            secondaryLabel = "结束",
        )
    }

    /** Route handled by [com.tiji.studyclock.MainActivity]; the capture form lives at entry/0. */
    const val ROUTE_TIMER = "timer"
    const val ROUTE_CAPTURE = "entry/0"
}
