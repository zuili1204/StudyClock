package com.tiji.studyclock.domain.stats

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/** All metrics except coverage and historical counts use [windowStart] through [today]. */
data class StatsSnapshot(
    val today: LocalDate,
    val zoneId: ZoneId,
    val windowStart: LocalDate,
    val accuracy: Metric<Double>,
    val secondsPerQuestion: Metric<Double>,
    val totalQuestions: Int,
    val totalSessions: Int,
    val netDuration: Metric<Long>,
    val coverage: Metric<Double>,
    /** Historically practiced preset IDs; custom categories are counted separately. */
    val practicedCategories: Int,
    val customPracticedCategories: Int,
    val categoryStats: List<CategoryStats>,
    val sessionStats: List<SessionStats>,
    val wilsonLowerBound: Metric<Double>,
    val sampleSufficiency: Metric<Double>,
    val timeDistribution: Metric<List<TimeSlotStats>>,
    /** Hour of day, pooled across weekdays; ties are resolved by ascending hour. */
    val goldenHour: Metric<Int>,
    val hourStats: List<HourStats>,
    val speedBaselines: Map<Long, SpeedBaseline>,
    val weakestCategoryIds: List<Long>,
    val sessionIds: List<Long>,
    /** v5：投入面（全部会话）的题量与记录 ID；正确率系列字段只覆盖 COUNT + NORMAL。 */
    val activityQuestions: Int = 0,
    val activitySessionIds: List<Long> = emptyList(),
) {
    val timedSeconds: Long? get() = netDuration.value
    val totalPracticedCategories: Int get() = practicedCategories + customPracticedCategories
}

data class CategoryStats(
    val categoryId: Long,
    val subjectId: Long,
    val accuracy: Metric<Double>,
    val secondsPerQuestion: Metric<Double>,
    val trend: Metric<Double>,
    val volatility: Metric<Double>,
    val sessionIds: List<Long>,
    val wilsonLowerBound: Metric<Double>,
    val sampleSufficiency: Metric<Double>,
    val netDuration: Metric<Long>,
    val speedStability: Metric<Double>,
    val practiceIntervals: Metric<List<Long>>,
    val sessionStats: List<SessionStats>,
    /** Historical date is kept separately from window-based metrics. */
    val lastPracticedDate: LocalDate?,
    val daysSinceLastPractice: Long?,
    val historicalSessionIds: List<Long>,
    /** D3/D4 are intentionally unavailable, irrespective of sample size. */
    val forgettingEstimate: Metric<Double>,
    val marginalBenefit: Metric<Double>,
) {
    val timedSeconds: Long? get() = netDuration.value
}

data class SessionStats(
    val sessionId: Long,
    val categoryId: Long,
    val date: LocalDate,
    val accuracy: Metric<Double>,
)

data class TimeSlotStats(
    val dayOfWeek: DayOfWeek,
    val hour: Int,
    val questionCount: Int,
    val sessionCount: Int,
    val sessionIds: List<Long>,
)

data class HourStats(
    val hour: Int,
    val accuracy: Metric<Double>,
    val sessionIds: List<Long>,
)

data class SpeedBaseline(
    val subjectId: Long,
    val secondsPerQuestion: Metric<Double>,
    val categoryIds: List<Long>,
    val sessionIds: List<Long>,
)
