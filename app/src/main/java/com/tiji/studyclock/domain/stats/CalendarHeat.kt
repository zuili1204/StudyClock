package com.tiji.studyclock.domain.stats

import java.time.LocalDate
import java.time.YearMonth

/**
 * 月历着色口径。四选一，任何时候只按一种口径着色：
 * 题量与用时是**投入面**的两种量法，正确率与得分率是两条**互不合并**的表现轨。
 * 不提供「综合强度」，也不允许把两条表现轨平均。
 */
enum class HeatMetric(val label: String, val isRate: Boolean, val note: String) {
    QUESTIONS("题量", false, "复做计入题量，专注计 0 题"),
    MINUTES("用时", false, "专注时长计入；未计时的补记不计入"),
    ACCURACY("正确率", true, "只算客观题；四档 <50% / 50–70% / 70–85% / ≥85%"),
    SCORE("得分率", true, "只算得分记录（Σ得分 ÷ Σ满分）；四档同正确率"),
}

/** 一个方格的状态：没有记录、有记录但没有该口径的样本、或落在某个强度档。 */
sealed interface HeatCell {
    /** 当天没有任何记录，方格不着色、不可点开。 */
    data object NoRecord : HeatCell

    /** 当天有记录，但当前口径没有可比样本（例如只看正确率，而这天只有专注或得分）。 */
    data object NoSample : HeatCell

    /** 0–3 四档，越大越深；[value] 是当前口径的原始值，供无障碍描述与详情使用。 */
    data class Level(val band: Int, val value: Double) : HeatCell
}

/**
 * 月历的唯一取数口径（纯 JVM）。UI 只负责把 [HeatCell] 画成颜色，
 * 不在组合函数里另算一套比例或分档，避免「题量对得上、颜色对不上」。
 */
object CalendarHeat {
    const val BANDS = 4

    fun value(summary: DaySummary, metric: HeatMetric): Double? = when (metric) {
        HeatMetric.QUESTIONS -> summary.questions.toDouble()
        HeatMetric.MINUTES -> summary.timedSeconds / 60.0
        HeatMetric.ACCURACY -> summary.accuracy
        HeatMetric.SCORE -> summary.scoreRate
    }

    /** 投入面按本月最高一天等分；表现轨用固定阈值，才不会被「这个月都很差」拉成满格。 */
    fun monthMax(days: Map<LocalDate, DaySummary>, metric: HeatMetric): Double =
        days.values.mapNotNull { value(it, metric)?.takeIf { it > 0 } }.maxOrNull() ?: 0.0

    fun cell(summary: DaySummary?, metric: HeatMetric, monthMax: Double): HeatCell {
        if (summary == null || !summary.hasRecords) return HeatCell.NoRecord
        val value = value(summary, metric) ?: return HeatCell.NoSample
        // 有记录但该口径是 0（只在专注、只有未计时补记）也走描边，避免读成「练了一点点」。
        if (!metric.isRate && value <= 0.0) return HeatCell.NoSample
        val band = if (metric.isRate) bandOfRate(value) else bandOfRatio(value, monthMax)
        return HeatCell.Level(band, value)
    }

    fun bandOfRatio(value: Double, monthMax: Double): Int = when {
        value <= 0.0 || monthMax <= 0.0 -> 0
        value / monthMax <= 0.25 -> 0
        value / monthMax <= 0.5 -> 1
        value / monthMax <= 0.75 -> 2
        else -> 3
    }

    fun bandOfRate(rate: Double): Int = when {
        rate < 0.5 -> 0
        rate < 0.7 -> 1
        rate < 0.85 -> 2
        else -> 3
    }
}

/**
 * 一个月的节律事实：覆盖率、最长连续、中断段数与最长中断。
 *
 * 口径：
 * - [elapsedDays] 对过去月份是整月天数，对本月是「今天几号」——未来日期不算没学；
 * - 中断只统计**第一次记录到最近一次记录之间**的空档，开头还没开始、结尾今天还没学都不算断档，
 *   否则每个月都会被判成「断档中」。
 */
data class MonthRhythm(
    val trackedDays: Int,
    val elapsedDays: Int,
    val longestRun: Int,
    val gapCount: Int,
    val longestGap: Int,
) {
    val coverage: Double get() = if (elapsedDays > 0) trackedDays.toDouble() / elapsedDays else 0.0
    val hasRecords: Boolean get() = trackedDays > 0
}

object MonthRhythmRules {
    fun of(activeDates: Collection<LocalDate>, month: YearMonth, today: LocalDate): MonthRhythm {
        val thisMonth = YearMonth.from(today)
        val elapsed = when {
            month.isAfter(thisMonth) -> 0
            month == thisMonth -> today.dayOfMonth
            else -> month.lengthOfMonth()
        }
        if (elapsed == 0) return MonthRhythm(0, 0, 0, 0, 0)
        val active = activeDates.asSequence()
            .filter { YearMonth.from(it) == month }
            .map { it.dayOfMonth }
            .filter { it <= elapsed }
            .toSortedSet()
        if (active.isEmpty()) return MonthRhythm(0, elapsed, 0, 0, 0)

        var longestRun = 1
        var run = 1
        val ordered = active.toList()
        for (index in 1 until ordered.size) {
            run = if (ordered[index] - ordered[index - 1] == 1) run + 1 else 1
            if (run > longestRun) longestRun = run
        }

        var gapCount = 0
        var longestGap = 0
        var current = 0
        for (day in ordered.first()..ordered.last()) {
            if (day in active) {
                if (current > 0) {
                    gapCount++
                    if (current > longestGap) longestGap = current
                }
                current = 0
            } else {
                current++
            }
        }
        return MonthRhythm(active.size, elapsed, longestRun, gapCount, longestGap)
    }
}
