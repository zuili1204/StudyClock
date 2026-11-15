package com.tiji.studyclock.domain.stats

import com.tiji.studyclock.domain.model.DateAttribution
import com.tiji.studyclock.domain.model.ScoreRules
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionScopes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.sqrt

/** G 轨（计分）纯 JVM 计算；不读时钟、不碰 Android、不与 COUNT/REDO 轨合并。 */
data class ScoreCategoryStats(
    val categoryId: Long,
    /** G2：Σscore / Σmax，不是各次得分率的平均。 */
    val rate: Metric<Double>,
    /** G3：Σscore / 次数，单位为「分」。 */
    val averageScore: Metric<Double>,
    /** G4：各次得分率对序号的最小二乘斜率，≥3 次。 */
    val trend: Metric<Double>,
    /** G5：各次得分率总体标准差，≥3 次。 */
    val volatility: Metric<Double>,
    val sessionIds: List<Long>,
    /** 只有满分口径一致时才能与目标分比较（D22）。 */
    val maxScoreConsistent: Boolean,
    val commonMaxScoreTenths: Int?,
)

data class ScoreSnapshot(
    val today: LocalDate,
    val windowStart: LocalDate,
    val rate: Metric<Double>,
    val averageScore: Metric<Double>,
    val sessionCount: Int,
    val byCategory: Map<Long, ScoreCategoryStats>,
    val sessionIds: List<Long>,
    /** 最近 N 次（按时间倒序），供「距目标分」与目标分差展示。 */
    val recent: List<Session>,
) {
    val hasData: Boolean get() = sessionIds.isNotEmpty()
}

object ScoreEngine {
    const val RECENT_FOR_TARGET = 5

    fun calculate(
        sessions: List<Session>,
        today: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault(),
        attribution: DateAttribution = DateAttribution.START,
        windowDays: Long = Thresholds.WINDOW_DAYS,
    ): ScoreSnapshot {
        val windowStart = today.minusDays(windowDays - 1)
        val scored = SessionScopes.scored(sessions)
            .filter { !date(it, zoneId, attribution).isAfter(today) }
            .sortedWith(compareBy<Session> { it.startAt }.thenBy { it.id })
        val window = scored.filter { !date(it, zoneId, attribution).isBefore(windowStart) }
        val byCategory = window.groupBy { it.categoryId }.mapValues { (categoryId, rows) ->
            categoryStats(categoryId, rows)
        }
        return ScoreSnapshot(
            today = today,
            windowStart = windowStart,
            rate = weightedRate(window),
            averageScore = averageScore(window),
            sessionCount = window.size,
            byCategory = byCategory,
            sessionIds = window.map { it.id },
            recent = window.sortedWith(compareByDescending<Session> { it.startAt }.thenByDescending { it.id })
                .take(RECENT_FOR_TARGET),
        )
    }

    /** G6 只是登记项；这里提供纯函数但不纳入 ScoreSnapshot 输出，口径不明时返回 null。 */
    fun targetGap(
        target: Double?,
        targetMaxScoreTenths: Int?,
        sessions: List<Session>,
        take: Int = RECENT_FOR_TARGET,
    ): Double? {
        val goal = target ?: return null
        val max = targetMaxScoreTenths ?: return null
        val consistent = SessionScopes.scored(sessions)
            .filter { it.maxScoreTenths == max }
            .take(take)
        if (consistent.size < Thresholds.SERIES_MIN_SESSIONS) return null
        val average = consistent.sumOf { it.scoreTenths!! } / 10.0 / consistent.size
        return goal - average
    }

    private fun categoryStats(categoryId: Long, rows: List<Session>): ScoreCategoryStats {
        val rates = rows.map { ScoreRules.rate(it.scoreTenths, it.maxScoreTenths)!! }
        val maxes = rows.mapNotNull { it.maxScoreTenths }.toSet()
        return ScoreCategoryStats(
            categoryId = categoryId,
            rate = weightedRate(rows),
            averageScore = averageScore(rows),
            trend = metric(
                if (rates.size >= Thresholds.SERIES_MIN_SESSIONS) slope(rates) else null,
                rows, "比例/次", "得分趋势需要至少3次同类别计分记录",
            ),
            volatility = metric(
                if (rates.size >= Thresholds.SERIES_MIN_SESSIONS) populationStdDev(rates) else null,
                rows, "比例", "得分波动需要至少3次同类别计分记录",
            ),
            sessionIds = rows.map { it.id },
            maxScoreConsistent = maxes.size == 1,
            commonMaxScoreTenths = maxes.singleOrNull(),
        )
    }

    /** 加权得分率：Σscore / Σmax。满分口径不同时按满分加权，不取各次平均。 */
    private fun weightedRate(rows: List<Session>): Metric<Double> {
        val maxSum = rows.sumOf { it.maxScoreTenths!!.toLong() }
        val scoreSum = rows.sumOf { it.scoreTenths!!.toLong() }
        return metric(if (maxSum == 0L) null else scoreSum.toDouble() / maxSum, rows, "比例", "还没有计分记录")
    }

    /** 均分：Σscore / 次数，单位分（把 tenths 还原成 10 分制单位）。 */
    private fun averageScore(rows: List<Session>): Metric<Double> =
        metric(
            if (rows.isEmpty()) null else rows.sumOf { it.scoreTenths!!.toLong() } / 10.0 / rows.size,
            rows, "分", "还没有计分记录",
        )

    /** SCORE 轨的样本单位只取自己的「篇数 + 次数」，绝不借用 COUNT 轨的题目数。 */
    private fun <T> metric(value: T?, rows: List<Session>, unit: String, reason: String): Metric<T> {
        val samples = rows.sumOf { it.totalCount }
        val level = if (value == null) ConfidenceLevel.INSUFFICIENT else Thresholds.confidence(samples, rows.size)
        return Metric(value, level, samples, rows.size, unit, if (value == null) reason else null)
    }

    private fun date(session: Session, zoneId: ZoneId, attribution: DateAttribution): LocalDate =
        Instant.ofEpochMilli(if (attribution == DateAttribution.END) session.endAt ?: session.startAt else session.startAt)
            .atZone(zoneId).toLocalDate()

    private fun populationStdDev(values: List<Double>): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    private fun slope(values: List<Double>): Double {
        val center = (values.size - 1) / 2.0
        val mean = values.average()
        val numerator = values.withIndex().sumOf { (index, value) -> (index - center) * (value - mean) }
        val denominator = values.indices.sumOf { (it - center) * (it - center) }
        return numerator / denominator
    }
}
