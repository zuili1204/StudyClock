package com.tiji.studyclock.domain.timer

import java.util.Locale

/**
 * Lock-screen/notification copy and the wall-clock anchor the system chronometer counts from.
 * Kept free of Android types so the running/remaining display is covered by JVM tests; the
 * service only maps [chronometerBaseMillis] onto the platform notification.
 */
data class TimerNotice(
    val title: String,
    val text: String,
    val subText: String?,
    /** Null means the notification shows fixed text instead of a live chronometer. */
    val chronometerBaseMillis: Long?,
    val countDown: Boolean,
) {
    val usesChronometer: Boolean get() = chronometerBaseMillis != null
}

object TimerNoticeFactory {
    fun build(
        start: TimerStart,
        subjectName: String?,
        categoryName: String?,
        nowAt: Long,
        nowElapsed: Long,
        currentBootCount: Int = -1,
    ): TimerNotice {
        val elapsed = SessionTimer.elapsedSeconds(start, nowAt, nowElapsed, currentBootCount)
        val place = buildString {
            append(subjectName?.takeIf { it.isNotBlank() } ?: "已选科目")
            append(" · ")
            append(categoryName?.takeIf { it.isNotBlank() } ?: "已选类别")
        }
        // v0.11：专注与练习共用同一状态机，通知只在标题上换个词，不新增字段。
        val focus = start.focus
        if (start.isPaused) {
            return TimerNotice(
                title = if (focus) "专注已暂停" else "计时已暂停",
                text = "已用时 ${format(elapsed)} · $place",
                subText = null,
                chronometerBaseMillis = null,
                countDown = false,
            )
        }
        val target = start.targetSec
        if (target == null) {
            return TimerNotice(
                title = if (focus) "专注进行中" else "正在计时",
                text = place,
                subText = null,
                chronometerBaseMillis = nowAt - elapsed * 1000L,
                countDown = false,
            )
        }
        val remaining = (target - elapsed).coerceAtLeast(0)
        return if (remaining > 0) {
            TimerNotice(
                title = if (focus) "专注倒计时中" else "倒计时进行中",
                text = place,
                subText = "目标 ${format(target)}",
                chronometerBaseMillis = nowAt + remaining * 1000L,
                countDown = true,
            )
        } else {
            TimerNotice(
                title = if (focus) "本次专注已到" else "倒计时已到",
                text = "已用时 ${format(elapsed)} · $place",
                subText = null,
                chronometerBaseMillis = null,
                countDown = false,
            )
        }
    }

    fun format(totalSeconds: Int): String {
        val safe = totalSeconds.coerceAtLeast(0)
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        val seconds = safe % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }
}
