package com.tiji.studyclock.domain.prescribe

import com.tiji.studyclock.domain.model.Category
import com.tiji.studyclock.domain.model.DateAttribution
import com.tiji.studyclock.domain.model.ExamTarget
import com.tiji.studyclock.domain.model.PrescriptionType
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.UserSettings
import com.tiji.studyclock.domain.stats.CategoryStats
import com.tiji.studyclock.domain.stats.ConfidenceLevel
import com.tiji.studyclock.domain.stats.SpeedBaseline
import com.tiji.studyclock.domain.stats.StatsSnapshot
import com.tiji.studyclock.domain.stats.Thresholds
import com.tiji.studyclock.domain.stats.sessionDate
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil

/** 生成结果尚未落库；执行、完成与验证由后续状态机负责。 */
data class PlannedPrescription(
    val categoryId: Long,
    val type: PrescriptionType,
    val targetCount: Int,
    val targetMinutes: Int?,
    val targetAccuracy: Double?,
    val attribution: String,
    val reason: String,
    val dueAt: Long,
)

data class PrescriptionPlan(
    val prescriptions: List<PlannedPrescription>,
    val note: String?,
    val remainingQuestions: Int?,
    val remainingMinutes: Int?,
) {
    val isEmpty: Boolean get() = prescriptions.isEmpty()
}

/**
 * S4 生成层：先按优先级挑类别，再按用户当天剩余预算截断。
 * 原则：没有完整一组就不生成零题处方；没有可靠速度就不把分钟目标换算成题量；
 * 已掌握或波动过大的类别只给行为建议，不硬凑第六种处方。
 */
object PrescriptionPlanner {
    const val EXAM_URGENT_DAYS = 14L
    const val STALE_REVIEW_DAYS = 14L
    const val MAX_PER_DAY = 2
    const val MIN_MINUTES = 10
    const val SPEED_FACTOR = 0.90
    const val CAREFUL_FACTOR = 1.20

    fun plan(
        stats: StatsSnapshot,
        categories: List<Category>,
        sessions: List<Session>,
        settings: UserSettings,
        exam: ExamTarget,
        today: LocalDate,
        attribution: DateAttribution,
        activeCategoryIds: Set<Long> = emptySet(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): PrescriptionPlan = plan(
        categoryStats = stats.categoryStats,
        speedBaselines = stats.speedBaselines,
        categories = categories,
        sessions = sessions,
        settings = settings,
        exam = exam,
        today = today,
        attribution = attribution,
        activeCategoryIds = activeCategoryIds,
        zoneId = zoneId,
    )

    fun plan(
        categoryStats: List<CategoryStats>,
        speedBaselines: Map<Long, SpeedBaseline>,
        categories: List<Category>,
        sessions: List<Session>,
        settings: UserSettings,
        exam: ExamTarget,
        today: LocalDate,
        attribution: DateAttribution,
        activeCategoryIds: Set<Long> = emptySet(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): PrescriptionPlan {
        val todayRows = sessions.filter { sessionDate(it, attribution) == today }
        val doneQuestions = todayRows.sumOf { it.totalCount }
        val doneMinutes = todayRows.sumOf { it.durationSec ?: 0 } / 60
        val remainingQuestions = settings.dailyQuestionGoal?.let { (it - doneQuestions).coerceAtLeast(0) }
        val remainingMinutes = settings.dailyMinuteGoal?.let { (it - doneMinutes).coerceAtLeast(0) }
        val dueAt = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1

        val candidates = candidates(categoryStats, speedBaselines, categories, exam, today, activeCategoryIds)
        if (candidates.isEmpty()) {
            val daysToExam = exam.examDate?.let { ChronoUnit.DAYS.between(today, it) }
            val note = when {
                daysToExam != null && daysToExam in 0..EXAM_URGENT_DAYS ->
                    "距${exam.displayName}还剩 $daysToExam 天，但目前没有达到可信样本的类别；今天不扩新内容，先把已练类别的记录补完整。"
                categoryStats.isEmpty() -> "还没有可安排的类别；先添加类别或完成一次摸底。"
                else -> "当前类别都已安排，或表现稳定、波动较大，暂不新增处方。"
            }
            return PrescriptionPlan(emptyList(), note, remainingQuestions, remainingMinutes)
        }

        if (remainingQuestions == null && remainingMinutes == null) {
            // 没有题量上限时不做完整日程，只给一条自愿行动，并引导设置目标。
            val candidate = candidates.firstOrNull { it.type == PrescriptionType.BASELINE } ?: candidates.first()
            return PrescriptionPlan(
                listOf(candidate.toPrescription(dueAt)),
                "还没有每日题量目标，先给一条自愿行动；设置后才会按当天预算安排完整计划。",
                null,
                null,
            )
        }

        if (remainingQuestions == null) {
            val minutes = remainingMinutes!!
            if (minutes < MIN_MINUTES) {
                return PrescriptionPlan(
                    emptyList(),
                    "今天的时间目标只剩 $minutes 分钟，不够一组 $MIN_MINUTES 分钟；明天再安排。",
                    null,
                    minutes,
                )
            }
            val candidate = candidates.firstOrNull { candidate ->
                val needed = candidate.targetMinutes
                needed == null || needed <= minutes
            } ?: return PrescriptionPlan(
                    emptyList(),
                    "今天只剩 $minutes 分钟，现有类别都放不下一组完整练习；明天再安排。",
                    null,
                    minutes,
                )
            val note = if (candidate.targetMinutes == null) {
                "只有分钟目标，且这个类别还没有可靠速度，先按一组不限时；计时后再换算时间。设置每日题量目标后才会按预算安排。"
            } else {
                "只有分钟目标，已按剩余 $minutes 分钟挑了一组；设置每日题量目标后才会按预算安排。"
            }
            return PrescriptionPlan(
                listOf(candidate.toPrescription(dueAt)),
                note,
                null,
                minutes,
            )
        }

        if (remainingQuestions < PrescriptionType.BASELINE.perSet) {
            return PrescriptionPlan(
                emptyList(),
                "今天的目标只剩 $remainingQuestions 题，不够一组 ${PrescriptionType.BASELINE.perSet} 题；明天再安排，或调高目标。",
                remainingQuestions,
                remainingMinutes,
            )
        }

        var leftQuestions = remainingQuestions
        var leftMinutes = remainingMinutes
        val picked = mutableListOf<PlannedPrescription>()
        for (candidate in candidates) {
            if (picked.size >= MAX_PER_DAY) break
            if (candidate.type.targetCount > leftQuestions) continue
            val minutes = candidate.targetMinutes
            if (minutes != null && leftMinutes != null && minutes > leftMinutes) continue
            picked += candidate.toPrescription(dueAt)
            leftQuestions -= candidate.type.targetCount
            if (minutes != null && leftMinutes != null) leftMinutes -= minutes
        }
        return PrescriptionPlan(
            picked,
            if (picked.isEmpty()) "今天剩 $remainingQuestions 题，但现有类别都放不下一组完整练习；明天再安排。" else null,
            remainingQuestions,
            remainingMinutes,
        )
    }

    private fun candidates(
        categoryStats: List<CategoryStats>,
        speedBaselines: Map<Long, SpeedBaseline>,
        categories: List<Category>,
        exam: ExamTarget,
        today: LocalDate,
        activeCategoryIds: Set<Long>,
    ): List<Candidate> {
        val catalog = categories.associateBy { it.id }
        val daysToExam = exam.examDate?.let { ChronoUnit.DAYS.between(today, it) }
        val urgent = daysToExam != null && daysToExam in 0..EXAM_URGENT_DAYS
        val rows = categoryStats.mapNotNull { stats ->
            val category = catalog[stats.categoryId] ?: return@mapNotNull null
            if (category.archived || stats.categoryId in activeCategoryIds) return@mapNotNull null
            val accuracy = stats.accuracy.value
            val trusted = stats.accuracy.level == ConfidenceLevel.TRUSTED && accuracy != null
            val practiced = stats.historicalSessionIds.isNotEmpty()
            val staleDays = stats.daysSinceLastPractice
            val name = category.name
            val speed = stats.secondsPerQuestion.value
            val baseline = speedBaselines[stats.subjectId]?.secondsPerQuestion?.value
            val speedReliable = speed != null &&
                stats.secondsPerQuestion.level == ConfidenceLevel.TRUSTED &&
                baseline != null
            val faster = speedReliable && Thresholds.isFast(speed!!, baseline!!)
            val slower = speedReliable && Thresholds.isSlow(speed!!, baseline!!)

            if (urgent) {
                // 临考前只复盘已练且可诊断的类别，不在这时开新类别。
                if (!trusted) return@mapNotNull null
                return@mapNotNull Candidate(
                    categoryId = stats.categoryId,
                    tier = 0,
                    rank = stats.wilsonLowerBound.value ?: 1.0,
                    type = PrescriptionType.REVIEW,
                    speed = null,
                    targetAccuracy = accuracy,
                    attribution = "距${exam.displayName}还剩 $daysToExam 天；只安排已练且可信的类别复测。",
                    reason = "一组复测，优先稳住已练内容，不再扩新类别。",
                )
            }
            if (practiced && staleDays != null && staleDays > STALE_REVIEW_DAYS) {
                return@mapNotNull Candidate(
                    categoryId = stats.categoryId,
                    tier = 1,
                    rank = -staleDays.toDouble(),
                    type = PrescriptionType.REVIEW,
                    speed = null,
                    targetAccuracy = accuracy,
                    attribution = "$name 最近一次练习在 $staleDays 天前；隔得久了，先复测当前状态。",
                    reason = "一组复测，不赶时间；结果用于刷新基线。",
                )
            }
            if (!trusted) {
                val questionCount = stats.accuracy.questionCount
                val sessionCount = stats.accuracy.sessionCount
                val missingQuestions = (Thresholds.TRUSTED_QUESTIONS - questionCount).coerceAtLeast(0)
                val missingSessions = (Thresholds.TRUSTED_SESSIONS - sessionCount).coerceAtLeast(0)
                val attribution = if (!practiced) {
                    "$name 还没有记录；先用一组题建立基线。"
                } else {
                    "$name 近 ${Thresholds.WINDOW_DAYS} 天 $questionCount 题、$sessionCount 次，" +
                        "距可信样本还差 $missingQuestions 题 / $missingSessions 次。"
                }
                return@mapNotNull Candidate(
                    categoryId = stats.categoryId,
                    tier = 2,
                    rank = stats.historicalSessionIds.size * 1000.0 + questionCount,
                    type = PrescriptionType.BASELINE,
                    speed = null,
                    targetAccuracy = null,
                    attribution = attribution,
                    reason = "不限时，做完记录题量与答对数；不设正确率目标。",
                )
            }

            val volatility = stats.volatility.value
            if (volatility != null && stats.volatility.level == ConfidenceLevel.TRUSTED && Thresholds.isLargeVolatility(volatility)) {
                return@mapNotNull null
            }
            val rate = accuracy!!
            val high = rate >= Thresholds.HIGH_ACCURACY
            val steady = stats.trend.value?.let(Thresholds::isSteady) == true
            if (high && steady && faster) return@mapNotNull null

            val type = when {
                rate < Thresholds.LOW_ACCURACY && faster -> PrescriptionType.CAREFUL_READING
                rate < Thresholds.LOW_ACCURACY -> PrescriptionType.KNOWLEDGE
                slower -> PrescriptionType.SPEED
                else -> PrescriptionType.REVIEW
            }
            val percent = String.format(Locale.CHINA, "%.1f", rate * 100)
            val speedText = when {
                slower -> "，单题 ${decimal(speed!!)} 秒，比同科目可信中位数慢"
                faster -> "，单题 ${decimal(speed!!)} 秒，比同科目可信中位数快"
                else -> ""
            }
            val attribution = "$name 近 ${Thresholds.WINDOW_DAYS} 天正确率 $percent%$speedText。"
            val target = speedFor(type, speed, speedReliable)
            return@mapNotNull Candidate(
                categoryId = stats.categoryId,
                tier = 3,
                rank = rate,
                type = type,
                speed = target,
                targetAccuracy = rate,
                attribution = attribution,
                reason = reasonFor(type, target),
            )
        }
        return rows.sortedWith(compareBy<Candidate> { it.tier }.thenBy { it.rank }.thenBy { it.categoryId })
    }

    private fun speedFor(type: PrescriptionType, speed: Double?, reliable: Boolean): Double? = when {
        !reliable || speed == null -> null
        type == PrescriptionType.SPEED -> speed * SPEED_FACTOR
        type == PrescriptionType.CAREFUL_READING -> speed * CAREFUL_FACTOR
        else -> null
    }

    private fun reasonFor(type: PrescriptionType, targetSpeed: Double?): String = when (type) {
        PrescriptionType.BASELINE -> "不限时，做完记录题量与答对数；不设正确率目标。"
        PrescriptionType.KNOWLEDGE -> "两组各 5 题，不限时；做完先回顾错题再继续。"
        PrescriptionType.SPEED -> "两组各 10 题，目标每题 ${decimal(targetSpeed!!)} 秒；先准确，再压缩到目标。"
        PrescriptionType.CAREFUL_READING -> "两组各 10 题，目标每题 ${decimal(targetSpeed!!)} 秒；答题时圈出关键词。"
        PrescriptionType.REVIEW -> "一组复测，确认当前表现；不据此归因。"
    }

    private fun decimal(value: Double): String = String.format(Locale.CHINA, "%.1f", value)

    private data class Candidate(
        val categoryId: Long,
        val tier: Int,
        val rank: Double,
        val type: PrescriptionType,
        val speed: Double?,
        val targetAccuracy: Double?,
        val attribution: String,
        val reason: String,
    ) {
        val targetMinutes: Int? get() = speed?.let { ceil(type.targetCount * it / 60.0).toInt().coerceAtLeast(1) }

        fun toPrescription(dueAt: Long) = PlannedPrescription(
            categoryId = categoryId,
            type = type,
            targetCount = type.targetCount,
            targetMinutes = targetMinutes,
            targetAccuracy = targetAccuracy,
            attribution = attribution,
            reason = reason,
            dueAt = dueAt,
        )
    }
}
