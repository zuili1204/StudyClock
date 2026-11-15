package com.tiji.studyclock.domain.diagnose

import com.tiji.studyclock.domain.model.Category
import com.tiji.studyclock.domain.stats.CategoryStats
import com.tiji.studyclock.domain.stats.ConfidenceLevel
import com.tiji.studyclock.domain.stats.StatsSnapshot
import com.tiji.studyclock.domain.stats.Thresholds
import java.util.Locale

data class Finding(
    val title: String,
    val detail: String,
    val action: String,
    val evidenceIds: List<Long>,
    val id: String,
    val ruleId: String,
    val confidence: ConfidenceLevel,
    val categoryId: Long? = null,
)

object DiagnosisGenerator {
    fun generate(snapshot: StatsSnapshot, categories: List<Category>): List<Finding> {
        if (snapshot.totalSessions == 0) return listOf(
            Finding(
                title = "统计窗口暂无练习",
                detail = "${snapshot.windowStart}至${snapshot.today}暂无有效记录；历史覆盖与当前窗口分开统计。",
                action = "先记录练习题量与答对数；有真实计时时再记录净时长。",
                evidenceIds = emptyList(),
                id = "NO_DATA:window",
                ruleId = "NO_DATA",
                confidence = ConfidenceLevel.INSUFFICIENT,
            ),
        )
        val names = categories.sortedWith(compareBy<Category> { it.id }.thenBy { it.name }).associate { it.id to it.name }
        return snapshot.categoryStats.filter { it.sessionIds.isNotEmpty() }
            .sortedWith(compareByDescending<CategoryStats> { it.accuracy.level == ConfidenceLevel.TRUSTED }.thenBy { it.categoryId })
            .map { stats -> describe(snapshot, stats, names[stats.categoryId] ?: "未命名类别") }
    }

    private fun describe(snapshot: StatsSnapshot, stats: CategoryStats, name: String): Finding {
        val accuracy = stats.accuracy.value
        val countDetail = "${stats.accuracy.questionCount}题、${stats.accuracy.sessionCount}次"
        val facts = "$name：当前窗口$countDetail，加权正确率${accuracy?.let(::percent) ?: "不可用"}。"
        fun finding(rule: Rule, detail: String, action: String, evidence: List<Long> = stats.sessionIds): Finding = Finding(
            title = rule.title,
            detail = facts + detail,
            action = action,
            evidenceIds = evidence.distinct(),
            id = "${rule.name}:${stats.categoryId}",
            ruleId = rule.name,
            confidence = stats.accuracy.level,
            categoryId = stats.categoryId,
        )
        if (accuracy == null || stats.accuracy.level != ConfidenceLevel.TRUSTED) {
            val questions = (Thresholds.TRUSTED_QUESTIONS - stats.accuracy.questionCount).coerceAtLeast(0)
            val sessions = (Thresholds.TRUSTED_SESSIONS - stats.accuracy.sessionCount).coerceAtLeast(0)
            return finding(Rule.SAMPLE_INSUFFICIENT,
                "距可信门槛还需${questions}题 / ${sessions}次，两项须同时满足；当前仅作样本描述。",
                "继续记录同类别练习，同时补足题量和会话数。")
        }
        val volatility = stats.volatility.value
        val trend = stats.trend.value
        if (volatility != null && stats.volatility.level == ConfidenceLevel.TRUSTED && Thresholds.isLargeVolatility(volatility)) {
            return finding(Rule.VOLATILE, "各次正确率总体标准差${decimal(volatility * 100)}个百分点，达到波动观察阈值。",
                "建议在相近时段、相近题量下继续采样，核对练习条件。")
        }
        val gap = stats.practiceIntervals.value?.lastOrNull()
        val previous = stats.sessionStats.dropLast(1)
        val latestRate = stats.sessionStats.lastOrNull()?.accuracy?.value
        val previousRate = previous.lastOrNull()?.accuracy?.value
        if (trend != null && Thresholds.isDeclining(trend) && gap != null && gap > Thresholds.FRESH_DAYS &&
            previous.any { (it.accuracy.value ?: 0.0) >= Thresholds.HIGH_ACCURACY } &&
            latestRate != null && previousRate != null && latestRate < previousRate) {
            return finding(Rule.GAP_DECLINE, "最近两次间隔${gap}天；此前有高正确率记录，本次较上次低。",
                "建议复测并核对题目条件；仅凭间隔与下降不能确定原因。")
        }
        val age = stats.daysSinceLastPractice
        if (age != null && age > Thresholds.FRESH_DAYS) {
            return finding(Rule.STALE, "最近一次练习距今${age}天，不代表能力已下降。",
                "建议复测，先获取当前表现的记录。",
                stats.sessionIds + stats.historicalSessionIds.takeLast(1))
        }
        val speed = stats.secondsPerQuestion.value
        val baseline = snapshot.speedBaselines[stats.subjectId]
        val baselineSpeed = baseline?.secondsPerQuestion?.value
        if (speed == null || stats.secondsPerQuestion.level != ConfidenceLevel.TRUSTED ||
            baseline == null || baselineSpeed == null || baseline.secondsPerQuestion.level != ConfidenceLevel.TRUSTED ||
            trend == null || volatility == null || age == null ||
            stats.trend.level != ConfidenceLevel.TRUSTED || stats.volatility.level != ConfidenceLevel.TRUSTED) {
            val missing = mutableListOf<String>()
            if (speed == null || stats.secondsPerQuestion.level != ConfidenceLevel.TRUSTED) {
                val q = (Thresholds.TRUSTED_QUESTIONS - stats.secondsPerQuestion.questionCount).coerceAtLeast(0)
                val s = (Thresholds.TRUSTED_SESSIONS - stats.secondsPerQuestion.sessionCount).coerceAtLeast(0)
                missing += "计时子集距可信门槛还需${q}题 / ${s}次"
            }
            if (baselineSpeed == null) missing += "同科目有效计时可信类别不足3个，暂无速度基准"
            if (trend == null || volatility == null) missing += "缺少至少3次同类别练习的趋势或波动"
            return finding(Rule.DIMENSIONS_UNAVAILABLE, missing.joinToString("；") + "；暂不比较快慢或归因。",
                "继续积累所缺维度，计时只使用真实有效的净时长。")
        }
        val speedDetail = "加权单题耗时${decimal(speed)}秒，同科目可信类别中位数${decimal(baselineSpeed)}秒；仅为观察。"
        val speedEvidence = stats.sessionIds + baseline.sessionIds
        val steady = Thresholds.isSteady(trend)
        val fast = Thresholds.isFast(speed, baselineSpeed)
        val slow = Thresholds.isSlow(speed, baselineSpeed)
        if (accuracy < Thresholds.LOW_ACCURACY && steady && fast) {
            return finding(Rule.LOW_FAST, speedDetail, "核对错题与计时记录，保持题目条件可比后再观察；不据快慢判断原因。", speedEvidence)
        }
        if (accuracy < Thresholds.LOW_ACCURACY && steady && slow) {
            return finding(Rule.LOW_SLOW, speedDetail, "核对错题与计时记录，检查练习条件是否一致；不据耗时推断知识缺口。", speedEvidence)
        }
        if (accuracy >= Thresholds.HIGH_ACCURACY && Thresholds.isRising(trend) && slow) {
            return finding(Rule.HIGH_SLOW_RISING, speedDetail, "继续观察相近题目条件下的正确率和耗时，不急于调整训练目标。", speedEvidence)
        }
        if (accuracy >= Thresholds.HIGH_ACCURACY && steady && fast) {
            return finding(Rule.HIGH_FAST_STEADY, speedDetail, "保留后续复测记录，检查表现是否持续；不据此断言掌握程度。", speedEvidence)
        }
        return when {
            Thresholds.isDeclining(trend) -> finding(Rule.DECLINING, "各次正确率对练习序号的斜率为${decimal(trend * 100)}个百分点/次。",
                "核对最近练习的题目条件，并继续记录以确认变化。")
            Thresholds.isRising(trend) -> finding(Rule.RISING, "各次正确率对练习序号的斜率为${decimal(trend * 100)}个百分点/次。",
                "继续在可比条件下观察，暂不把变化归因于某种训练。")
            accuracy < Thresholds.LOW_ACCURACY -> finding(Rule.LOW_ACCURACY, "正确率低于70%的产品观察阈值。", "核对错题记录，积累可比样本，不强行归因。")
            accuracy >= Thresholds.HIGH_ACCURACY -> finding(Rule.HIGH_ACCURACY, "正确率达到85%的产品观察阈值。", "保留后续复测记录，确认当前表现是否持续。")
            else -> finding(Rule.OBSERVATION, "趋势在平稳阈值内，只描述当前样本。", "继续记录可比练习，观察正确率与耗时变化。")
        }
    }

    private fun percent(value: Double): String = "${decimal(value * 100)}%"
    private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    private enum class Rule(val title: String) {
        SAMPLE_INSUFFICIENT("样本尚不足以支持诊断"),
        VOLATILE("各次正确率波动较大"),
        GAP_DECLINE("间隔后本次正确率较低"),
        STALE("近期未练习，建议复测"),
        DIMENSIONS_UNAVAILABLE("维度不足，暂不比较快慢"),
        LOW_FAST("正确率偏低，单题耗时较短"),
        LOW_SLOW("正确率偏低，单题耗时较长"),
        HIGH_SLOW_RISING("正确率较高且上升，耗时较长"),
        HIGH_FAST_STEADY("正确率较高平稳，耗时较短"),
        DECLINING("当前窗口正确率趋势下降"),
        RISING("当前窗口正确率趋势上升"),
        LOW_ACCURACY("当前样本正确率偏低"),
        HIGH_ACCURACY("当前样本正确率较高"),
        OBSERVATION("当前样本趋势平稳"),
    }
}
