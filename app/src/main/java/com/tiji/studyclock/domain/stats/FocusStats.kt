package com.tiji.studyclock.domain.stats

import com.tiji.studyclock.domain.model.DateAttribution
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionScopes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 单个类别的专注时长，只作同期上下文，不参与表现轨计算。 */
data class FocusCategoryRow(
    val categoryId: Long,
    val seconds: Long,
    val sessions: Int,
)

/**
 * P1–P5：专注只回答“投入了多久”。
 * 全部数值来自 FOCUS 记录的净时长，不做效率、质量或跨轨加权。
 */
data class FocusSnapshot(
    val totalSeconds: Long = 0,
    val sessionCount: Int = 0,
    val averageSeconds: Long? = null,
    /** 专注净时长 / 全部 activity 净时长；总时长为 0 时为 null。 */
    val shareOfStudyTime: Double? = null,
    /** 全部历史中有专注记录的日期最长连续段。 */
    val longestStreakDays: Int = 0,
    val sessionIds: List<Long> = emptyList(),
    val byCategory: List<FocusCategoryRow> = emptyList(),
)

object FocusStats {
    fun calculate(
        sessions: List<Session>,
        today: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault(),
        attribution: DateAttribution = DateAttribution.START,
        windowDays: Long = Thresholds.WINDOW_DAYS,
    ): FocusSnapshot {
        val windowStart = today.minusDays(windowDays - 1)
        fun date(session: Session): LocalDate =
            Instant.ofEpochMilli(
                if (attribution == DateAttribution.END) session.endAt ?: session.startAt else session.startAt,
            ).atZone(zoneId).toLocalDate()

        val focusHistory = SessionScopes.focus(sessions)
            .filter { !date(it).isAfter(today) }
            .sortedWith(compareBy<Session> { it.startAt }.thenBy { it.id })
        val window = focusHistory.filter { !date(it).isBefore(windowStart) }
        val totalSeconds = window.sumOf { it.durationSec!!.toLong() }
        val count = window.size
        val average = if (count >= 2) totalSeconds / count else null
        val allSeconds = SessionScopes.activity(sessions)
            .filter { !date(it).isAfter(today) && !date(it).isBefore(windowStart) }
            .sumOf { (it.durationSec ?: 0).toLong() }
        val streak = Consistency.summarize(focusHistory, today, attribution).longest
        val byCategory = window.groupBy { it.categoryId }
            .map { (categoryId, rows) ->
                FocusCategoryRow(
                    categoryId = categoryId,
                    seconds = rows.sumOf { it.durationSec!!.toLong() },
                    sessions = rows.size,
                )
            }
            .sortedWith(compareByDescending<FocusCategoryRow> { it.seconds }.thenBy { it.categoryId })

        return FocusSnapshot(
            totalSeconds = totalSeconds,
            sessionCount = count,
            averageSeconds = average,
            shareOfStudyTime = if (allSeconds > 0) totalSeconds.toDouble() / allSeconds else null,
            longestStreakDays = streak,
            sessionIds = window.map { it.id },
            byCategory = byCategory,
        )
    }
}
