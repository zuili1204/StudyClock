package com.tiji.studyclock.domain.stats

import com.tiji.studyclock.domain.model.DateAttribution
import com.tiji.studyclock.domain.model.Session
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 打卡连续性：当前连击、最长连击、窗口内活跃天数。 */
data class StreakSummary(
    val current: Int,
    val longest: Int,
    val activeDays: Int,
    val windowDays: Int,
    val lastActive: LocalDate?,
) {
    fun daysSinceLast(today: LocalDate): Long? = lastActive?.let { today.toEpochDay() - it.toEpochDay() }
}

/**
 * 记录归属日期的唯一实现：UI、统计、导出都调用它，避免各算一套。
 * 跨零点记录按设置归到开始日或结束日。
 */
fun sessionDate(session: Session, attribution: DateAttribution): LocalDate =
    Instant.ofEpochMilli(if (attribution == DateAttribution.END) session.endAt ?: session.startAt else session.startAt)
        .atZone(ZoneId.systemDefault()).toLocalDate()

/**
 * 只看“有记录的日期”，不引入任何预测。连击口径：
 * - 今天有记录 → 从今天往前数；
 * - 今天没有、昨天有 → 从昨天往前数（当天尚未结束，不算断）；
 * - 否则为 0。最长连击按全部历史计算，活跃天数按窗口计算。
 */
object Consistency {
    fun summarize(
        sessions: List<Session>,
        today: LocalDate,
        attribution: DateAttribution = DateAttribution.START,
        windowDays: Int = 30,
    ): StreakSummary {
        if (sessions.isEmpty()) return StreakSummary(0, 0, 0, windowDays, null)
        val dates = sessions.map { sessionDate(it, attribution) }.toSet()
        val sorted = dates.sorted()
        var longest = 1
        var run = 1
        for (index in 1 until sorted.size) {
            run = if (sorted[index].toEpochDay() - sorted[index - 1].toEpochDay() == 1L) run + 1 else 1
            if (run > longest) longest = run
        }
        var cursor: LocalDate? = when {
            dates.contains(today) -> today
            dates.contains(today.minusDays(1)) -> today.minusDays(1)
            else -> null
        }
        var current = 0
        while (cursor != null && dates.contains(cursor)) {
            current++
            cursor = cursor.minusDays(1)
        }
        val windowStart = today.minusDays(windowDays.toLong() - 1)
        val activeDays = dates.count { !it.isBefore(windowStart) && !it.isAfter(today) }
        return StreakSummary(current, longest, activeDays, windowDays, sorted.last())
    }
}
