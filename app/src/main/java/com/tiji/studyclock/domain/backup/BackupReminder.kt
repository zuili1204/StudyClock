package com.tiji.studyclock.domain.backup

import java.time.Instant
import java.time.temporal.ChronoUnit

data class BackupReminderState(
    val due: Boolean = false,
    val neverBackedUp: Boolean = false,
    val daysSince: Long? = null,
)

/**
 * 只在应用打开时判断，不引入后台定时任务。
 * 从未备份过时，以最早一条记录的时间起算，避免新用户第一天就被提醒。
 */
object BackupReminder {
    const val INTERVAL_DAYS = 14L
    const val DISMISS_DAYS = 7L

    fun evaluate(
        lastBackupAt: Long?,
        dismissedAt: Long?,
        oldestSessionAt: Long?,
        now: Long,
    ): BackupReminderState {
        if (oldestSessionAt == null) return BackupReminderState()
        val never = lastBackupAt == null
        val days = daysBetween(lastBackupAt ?: oldestSessionAt, now)
        if (days < INTERVAL_DAYS) return BackupReminderState(daysSince = days.coerceAtLeast(0))
        if (dismissedAt != null && daysBetween(dismissedAt, now) < DISMISS_DAYS) {
            return BackupReminderState(daysSince = days)
        }
        return BackupReminderState(due = true, neverBackedUp = never, daysSince = days.coerceAtLeast(0))
    }

    private fun daysBetween(from: Long, to: Long): Long =
        ChronoUnit.DAYS.between(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to))
}
