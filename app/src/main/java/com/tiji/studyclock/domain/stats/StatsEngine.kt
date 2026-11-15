package com.tiji.studyclock.domain.stats

import com.tiji.studyclock.domain.model.Category
import com.tiji.studyclock.domain.model.DateAttribution
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionScopes
import com.tiji.studyclock.domain.model.SessionSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.sqrt

/**
 * 纯 JVM 统计。无时钟读取、无持久化、无 Android、不从缺失时长推断。
 * v5 起样本统一经 [SessionScopes] 切分：
 * 题量/时长/时段/热力/连击吃 activity（全部会话），正确率系列只吃 performance（COUNT + NORMAL）。
 * 旧的全客观题输入下两个子集相同，所有既有口径与断言保持不变。
 */
object StatsEngine {
    fun calculate(
        sessions: List<Session>,
        categories: List<Category>,
        today: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault(),
        attribution: DateAttribution = DateAttribution.START,
    ): StatsSnapshot {
        val windowStart = today.minusDays(Thresholds.WINDOW_DAYS - 1)
        // Invalid counts cannot contribute evidence; FOCUS has no count and is valid via duration.
        val history = sessions.asSequence()
            .filter(SessionScopes::isActivity)
            .filter { !date(it, zoneId, attribution).isAfter(today) }
            .sortedWith(sessionOrder)
            .toList()
        val window = history.filter { !date(it, zoneId, attribution).isBefore(windowStart) }
        // 统一过滤层：投入面与表现面在同一份有序数据上切分，避免各页面各写一遍 filter。
        val performance = SessionScopes.performance(window)
        val historyByCategory = SessionScopes.performance(history).groupBy { it.categoryId }
        val windowByCategory = performance.groupBy { it.categoryId }
        val catalog = categories.sortedWith(compareBy<Category> { it.id }.thenBy { it.subjectId }.thenBy { it.name })
            .associateBy { it.id }
        val categoryStats = (catalog.keys + historyByCategory.keys).sorted().map { categoryId ->
            val past = historyByCategory[categoryId].orEmpty()
            categoryStats(
                categoryId,
                catalog[categoryId]?.subjectId ?: past.last().subjectId,
                windowByCategory[categoryId].orEmpty(),
                past,
                today,
                zoneId,
                attribution,
            )
        }
        val presetHistory = history.filter { it.categoryId in Thresholds.PRESET_CATEGORY_IDS }
        val practiced = presetHistory.map { it.categoryId }.distinct().size
        val customPracticed = history.asSequence()
            .filter { it.categoryId !in Thresholds.PRESET_CATEGORY_IDS }
            .map { it.categoryId }.distinct().count()
        val realStarts = window.filter(::hasRealStart)
        val distribution = realStarts.groupBy {
            val local = Instant.ofEpochMilli(it.startAt).atZone(zoneId)
            local.dayOfWeek to local.hour
        }.entries.sortedWith(compareBy({ it.key.first.value }, { it.key.second })).map { (slot, records) ->
            TimeSlotStats(slot.first, slot.second, questions(records), records.size, records.map { it.id })
        }
        val hourlyRecords = realStarts.groupBy { Instant.ofEpochMilli(it.startAt).atZone(zoneId).hour }
        val hours = hourlyRecords.toSortedMap().map { (hour, records) ->
            HourStats(hour, accuracy(SessionScopes.performance(records)), records.map { it.id })
        }
        val bestHour = hours.filter { it.accuracy.level == ConfidenceLevel.TRUSTED }
            .sortedWith(compareByDescending<HourStats> { it.accuracy.value }.thenBy { it.hour })
            .firstOrNull()
        val baselines = categoryStats.groupBy { it.subjectId }.toSortedMap().mapValues { (subjectId, stats) ->
            val eligible = stats.filter { it.secondsPerQuestion.level == ConfidenceLevel.TRUSTED }
            val ids = eligible.map { it.categoryId }
            val records = performance.filter { it.categoryId in ids && validDuration(it) }
            val speeds = eligible.mapNotNull { it.secondsPerQuestion.value }.sorted()
            val median = if (speeds.size < Thresholds.SPEED_BASELINE_MIN_CATEGORIES) null else {
                val middle = speeds.size / 2
                if (speeds.size % 2 == 0) (speeds[middle - 1] + speeds[middle]) / 2 else speeds[middle]
            }
            SpeedBaseline(
                subjectId,
                metric(median, records, "秒/题", "同科目需至少3个计时样本可信的类别"),
                ids,
                records.map { it.id },
            )
        }
        return StatsSnapshot(
            today = today,
            zoneId = zoneId,
            windowStart = windowStart,
            accuracy = accuracy(performance),
            // 速度只属于正确率轨；FOCUS/SCORE/REDO 不进分母（v0.11 同时修正 v5 遗留）。
            secondsPerQuestion = speed(performance),
            totalQuestions = questions(window),
            totalSessions = window.size,
            netDuration = duration(window),
            // No history is unknown; custom-only history establishes a real zero preset coverage.
            coverage = metric(
                if (history.isEmpty()) null else practiced.toDouble() / Thresholds.PRESET_CATEGORY_COUNT,
                presetHistory, "比例", "暂无历史练习记录",
            ),
            practicedCategories = practiced,
            customPracticedCategories = customPracticed,
            categoryStats = categoryStats,
            sessionStats = sessionStats(performance, zoneId, attribution),
            wilsonLowerBound = wilson(performance),
            sampleSufficiency = sufficiency(performance),
            timeDistribution = metric(distribution.takeIf { realStarts.isNotEmpty() }, realStarts, "题", "缺少真实起始时刻"),
            goldenHour = metric(
                bestHour?.hour,
                bestHour?.let { SessionScopes.performance(hourlyRecords[it.hour].orEmpty()) }.orEmpty(),
                "时", "没有时段达到30题且3次，仅提供观察不推断原因",
            ),
            hourStats = hours,
            speedBaselines = baselines,
            weakestCategoryIds = categoryStats.filter {
                it.accuracy.level != ConfidenceLevel.INSUFFICIENT && it.wilsonLowerBound.value != null
            }.sortedWith(compareBy<CategoryStats> { it.wilsonLowerBound.value }.thenBy { it.categoryId })
                .map { it.categoryId },
            sessionIds = window.map { it.id },
            // v5：投入面显式暴露，供三轨对照与「题量对得上」验收使用。
            activityQuestions = questions(window),
            activitySessionIds = window.map { it.id },
        )
    }

    private fun categoryStats(
        categoryId: Long,
        subjectId: Long,
        records: List<Session>,
        history: List<Session>,
        today: LocalDate,
        zoneId: ZoneId,
        attribution: DateAttribution,
    ): CategoryStats {
        val rates = records.map { it.correctCount.toDouble() / it.totalCount }
        val timed = records.filter(::validDuration)
        val speeds = timed.map { it.durationSec!!.toDouble() / it.totalCount }
        val lastDate = history.lastOrNull()?.let { date(it, zoneId, attribution) }
        val intervals = if (records.size < 2) null else records.zipWithNext { before, after ->
            ChronoUnit.DAYS.between(date(before, zoneId, attribution), date(after, zoneId, attribution))
        }
        return CategoryStats(
            categoryId = categoryId,
            subjectId = subjectId,
            accuracy = accuracy(records),
            secondsPerQuestion = speed(records),
            trend = metric(
                if (rates.size >= Thresholds.SERIES_MIN_SESSIONS) slope(rates) else null,
                records, "比例/次", "趋势需要至少3次同类别练习",
            ),
            volatility = metric(
                if (rates.size >= Thresholds.SERIES_MIN_SESSIONS) populationStdDev(rates) else null,
                records, "比例", "正确率波动需要至少3次同类别练习",
            ),
            sessionIds = records.map { it.id },
            wilsonLowerBound = wilson(records),
            sampleSufficiency = sufficiency(records),
            netDuration = duration(records),
            speedStability = metric(
                if (speeds.size >= Thresholds.SERIES_MIN_SESSIONS) populationStdDev(speeds) / speeds.average() else null,
                timed, "变异系数", "速度稳定性需要至少3次同类别有效计时练习",
            ),
            practiceIntervals = metric(intervals, records, "天", "练习间隔需要至少2次同类别练习"),
            sessionStats = sessionStats(records, zoneId, attribution),
            lastPracticedDate = lastDate,
            daysSinceLastPractice = lastDate?.let { ChronoUnit.DAYS.between(it, today) },
            historicalSessionIds = history.map { it.id },
            forgettingEstimate = metric(null, emptyList(), "未定义", "D3遗忘估计延期至v2，不输出估计"),
            marginalBenefit = metric(null, emptyList(), "未定义", "D4边际效益延期至v2，不输出估计"),
        )
    }

    private fun sessionStats(records: List<Session>, zoneId: ZoneId, attribution: DateAttribution): List<SessionStats> = records.map {
        SessionStats(it.id, it.categoryId, date(it, zoneId, attribution), accuracy(listOf(it)))
    }

    /** COUNT 轨：只在 performance 子集上调用；SCORE 与 REDO 永远进不来（质量闸 F）。 */
    private fun accuracy(records: List<Session>): Metric<Double> {
        val n = questions(records)
        val correct = records.sumOf { it.correctCount.toLong() }
        return metric(if (n == 0) null else correct.toDouble() / n, records, "比例", "暂无有效练习记录")
    }

    private fun wilson(records: List<Session>): Metric<Double> {
        val n = questions(records).toDouble()
        val p = accuracy(records).value
        val z = Thresholds.WILSON_Z
        val z2 = z * z
        val lower = p?.let {
            ((it + z2 / (2 * n) - z * sqrt(it * (1 - it) / n + z2 / (4 * n * n))) / (1 + z2 / n))
                .coerceIn(0.0, 1.0)
        }
        return metric(lower, records, "比例", "暂无有效练习记录")
    }

    private fun sufficiency(records: List<Session>): Metric<Double> = metric(
        if (records.isEmpty()) null else (questions(records).toDouble() / Thresholds.TRUSTED_QUESTIONS).coerceAtMost(1.0),
        records, "比例", "暂无有效练习记录",
    )

    private fun duration(records: List<Session>): Metric<Long> {
        val timed = records.filter(::validDuration)
        // durationSec is already net: do not subtract pausedSec or reconstruct missing durations.
        return metric(timed.takeIf { it.isNotEmpty() }?.sumOf { it.durationSec!!.toLong() }, timed, "秒", "缺少有效计时记录")
    }

    private fun speed(records: List<Session>): Metric<Double> {
        val timed = records.filter(::validDuration)
        val seconds = timed.sumOf { it.durationSec!!.toLong() }
        val questionCount = questions(timed)
        return metric(
            if (timed.isEmpty() || questionCount == 0) null else seconds.toDouble() / questionCount,
            timed, "秒/题", "缺少有效计时记录",
        )
    }

    private fun <T> metric(value: T?, records: List<Session>, unit: String, reason: String): Metric<T> {
        val n = questions(records)
        return Metric(value, if (value == null) ConfidenceLevel.INSUFFICIENT else Thresholds.confidence(n, records.size),
            n, records.size, unit, if (value == null) reason else null)
    }

    // Fail explicitly rather than silently overflowing the public Int question-count contract.
    private fun questions(records: List<Session>): Int = Math.toIntExact(records.sumOf { it.totalCount.toLong() })
    private fun validDuration(session: Session): Boolean = (session.durationSec ?: 0) > 0
    private fun date(session: Session, zoneId: ZoneId, attribution: DateAttribution): LocalDate =
        Instant.ofEpochMilli(if (attribution == DateAttribution.END) session.endAt ?: session.startAt else session.startAt)
            .atZone(zoneId).toLocalDate()

    // 补记只有在用户明确填写了起止时刻时才算“真实起始”，否则只有日期锚点。
    private fun hasRealStart(session: Session): Boolean = when (session.source) {
        SessionSource.MANUAL, SessionSource.IMPORT -> session.endAt?.let { it > session.startAt } == true
        SessionSource.TIMER -> true
    }

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

    // IDs disambiguate identical starts. Further keys keep unsaved (id=0) input order irrelevant.
    private val sessionOrder = compareBy<Session> { it.startAt }.thenBy { it.id }
        .thenBy { it.categoryId }.thenBy { it.subjectId }.thenBy { it.totalCount }
        .thenBy { it.correctCount }.thenBy { it.durationSec ?: 0 }.thenBy { it.source.ordinal }
        .thenBy { it.endAt ?: Long.MIN_VALUE }
}
