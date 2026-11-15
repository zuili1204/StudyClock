package com.tiji.studyclock.domain.diagnose

import com.tiji.studyclock.domain.model.Category
import com.tiji.studyclock.domain.model.DateAttribution
import com.tiji.studyclock.domain.model.ExamTarget
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.ReviewAttempt
import com.tiji.studyclock.domain.model.SessionScopes
import com.tiji.studyclock.domain.model.Subject
import com.tiji.studyclock.domain.model.TagRules
import com.tiji.studyclock.domain.model.WrongBatch
import com.tiji.studyclock.domain.stats.ConfidenceLevel
import com.tiji.studyclock.domain.stats.HourStats
import com.tiji.studyclock.domain.stats.RedoLedger
import com.tiji.studyclock.domain.stats.RedoMetrics
import com.tiji.studyclock.domain.stats.ScoreSnapshot
import com.tiji.studyclock.domain.stats.StreakSummary
import com.tiji.studyclock.domain.stats.sessionDate
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 发现层：把统计结果翻译成“可以行动的一句话”。
 * 所有阈值都是常量并配单测；样本不够时宁可不输出，也不猜。
 */
data class Insight(
    val id: String,
    val tone: Tone,
    val title: String,
    val detail: String,
) {
    enum class Tone { ATTENTION, NEUTRAL, POSITIVE }
}

object InsightGenerator {
    const val GAP_DAYS = 2L
    const val STREAK_MIN = 3
    const val HOUR_MIN_QUESTIONS = 20
    const val HOUR_MIN_GAP = 0.10
    const val STAGNATION_SESSIONS = 5
    const val STAGNATION_MIN_QUESTIONS = 30
    const val STAGNATION_DROP = 0.08
    const val COVERAGE_MIN_UNTOUCHED = 2
    const val GOAL_WINDOW_DAYS = 7L
    const val GOAL_LOW_DAYS = 2
    const val GOAL_HIGH_DAYS = 6
    const val TAG_WINDOW_DAYS = 14L
    const val TAG_MIN_SESSIONS = 3
    const val TAG_MIN_SHARE = 0.5
    const val MAX_INSIGHTS = 3

    // ---- v5 三轨联动发现（N1–N6）：阈值全部为常量，样本不足一律不输出 ----
    const val N1_PENDING_MIN = 20
    const val N1_OLD_DAYS = 7L
    const val N2_MIN_ROUNDS = 3
    const val N2_FIRST_PASS_LOW = 0.60
    const val N3_MIN_SCORED_SESSIONS = 3
    const val N3_LOW_SCORE_RATE = 0.60
    const val N4_TREND_DROP = -0.02
    const val N5_HIGH_ACCURACY = 0.85
    const val N6_MIN_REPEAT = 3
    const val N7_MIN_FOCUS = 3
    const val N7_DAYS = 7L
    const val CROSS_WINDOW_DAYS = 30L

    fun generate(
        sessions: List<Session>,
        categories: List<Category>,
        subjects: List<Subject>,
        exam: ExamTarget,
        hourStats: List<HourStats>,
        streak: StreakSummary,
        today: LocalDate,
        attribution: DateAttribution,
        dailyQuestionGoal: Int?,
        max: Int = MAX_INSIGHTS,
    ): List<Insight> {
        val found = mutableListOf<Insight>()
        gap(sessions, today, attribution, streak)?.let(found::add)
        streakInsight(streak)?.let(found::add)
        hourGap(hourStats)?.let(found::add)
        stagnation(sessions, categories)?.let(found::add)
        goal(sessions, today, attribution, dailyQuestionGoal)?.let(found::add)
        tagConcentration(sessions, today, attribution)?.let(found::add)
        coverage(sessions, subjects, exam, today)?.let(found::add)
        return found
            .sortedWith(compareBy({ it.tone.ordinal }, { it.id }))
            .take(max)
    }

    /**
     * v5 三轨联动发现。与既有规则分开生成、按同一 tone/id 规则合并取上限，避免改动原有规则的输入契约。
     * 只描述可核实事实，不推断因果；跨轨只并列，不合并。
     */
    fun generateCrossMode(
        sessions: List<Session>,
        categories: List<Category>,
        score: ScoreSnapshot,
        redo: RedoMetrics,
        batches: List<WrongBatch>,
        attempts: List<ReviewAttempt>,
        today: LocalDate,
        attribution: DateAttribution,
        zoneId: ZoneId = ZoneId.systemDefault(),
        max: Int = MAX_INSIGHTS,
    ): List<Insight> {
        val found = mutableListOf<Insight>()
        val names = categories.associate { it.id to it.name }
        val windowStart = today.minusDays(CROSS_WINDOW_DAYS - 1)

        // N1 复做积压
        if (redo.pendingQuestions >= N1_PENDING_MIN && (redo.oldestPendingDays ?: 0L) >= N1_OLD_DAYS) {
            val days = redo.oldestPendingDays ?: 0L
            val oldest = RedoLedger.ledgers(batches, attempts, today, zoneId)
                .filter { it.status == com.tiji.studyclock.domain.stats.BatchStatus.PENDING }
                .maxByOrNull { it.waitingDays ?: 0L }
            val where = oldest?.batch?.note?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
            found += Insight(
                id = "N1", tone = Insight.Tone.ATTENTION,
                title = "${redo.pendingQuestions} 道错题等了 $days 天",
                detail = "待复做 ${redo.pendingBatches} 袋，最久的一袋已等 $days 天$where。先清最久的一袋，不用一次补完。",
            )
        }

        // N2 一次通过率低（近 30 天，≥3 轮）
        val recentAttempts = attempts.filter {
            Instant.ofEpochMilli(it.createdAt).atZone(zoneId).toLocalDate() >= windowStart
        }
        val firstPass = redo.firstPassRate.value
        if (recentAttempts.size >= N2_MIN_ROUNDS && firstPass != null && firstPass < N2_FIRST_PASS_LOW) {
            found += Insight(
                id = "N2", tone = Insight.Tone.ATTENTION,
                title = "重做仍有 ${Math.round((1 - firstPass) * 100)} 成没过",
                detail = "近 $CROSS_WINDOW_DAYS 天 ${recentAttempts.size} 轮复做，一次通过率 ${Math.round(firstPass * 100)}%。先回看关键词再重做。",
            )
        }

        // N3 计分类别低分
        val lowScored = score.byCategory.values.filter {
            it.rate.sessionCount >= N3_MIN_SCORED_SESSIONS && (it.rate.value ?: 1.0) < N3_LOW_SCORE_RATE
        }.minByOrNull { it.rate.value ?: 1.0 }
        if (lowScored != null) {
            val percent = Math.round((lowScored.rate.value ?: 0.0) * 100)
            found += Insight(
                id = "N3", tone = Insight.Tone.ATTENTION,
                title = "「${names[lowScored.categoryId] ?: "计分类别"}」平均得分率 $percent%",
                detail = "近窗口 ${lowScored.rate.sessionCount} 次计分，加权得分率 $percent%。对照目标分重写一次。",
            )
        }

        // N4 得分下滑
        val declining = score.byCategory.values.filter {
            it.trend.value != null && it.trend.value!! < N4_TREND_DROP && it.trend.sessionCount >= N3_MIN_SCORED_SESSIONS
        }.minByOrNull { it.trend.value!! }
        if (declining != null) {
            found += Insight(
                id = "N4", tone = Insight.Tone.ATTENTION,
                title = "「${names[declining.categoryId] ?: "计分类别"}」得分在往下走",
                detail = "近窗口 ${declining.trend.sessionCount} 次计分，斜率 ${String.format(Locale.CHINA, "%.3f", declining.trend.value)}/次。回看最近 3 次计分记录。",
            )
        }

        // N5 分轨背离：只描述对照，不推断因果；两轨各 ≥REFERENCE
        val divergence = score.byCategory.values.firstOrNull { stat ->
            val objective = stat.categoryId.let { id -> objectiveAccuracy(sessions, id) }
            objective != null && objective >= N5_HIGH_ACCURACY &&
                stat.rate.level != ConfidenceLevel.INSUFFICIENT &&
                (stat.rate.value ?: 1.0) < N3_LOW_SCORE_RATE
        }
        if (divergence != null) {
            found += Insight(
                id = "N5", tone = Insight.Tone.NEUTRAL,
                title = "「${names[divergence.categoryId] ?: "该类别"}」客观题稳、主观题偏低",
                detail = "同一类别两条轨并列：正确率 ${Math.round(objectiveAccuracy(sessions, divergence.categoryId)!! * 100)}%，" +
                    "得分率 ${Math.round((divergence.rate.value ?: 0.0) * 100)}%。只是对照，不推断原因；拆分训练。",
            )
        }

        // N6 错因复现：同一关键词在 ≥3 次复做/计分记录中出现
        val repeated = SessionScopes.otherTagged(sessions)
            .flatMap { session -> TagRules.parse(session.wrongTags) }
            .groupingBy { it }.eachCount().entries
            .filter { it.value >= N6_MIN_REPEAT }
            .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        if (repeated != null) {
            found += Insight(
                id = "N6", tone = Insight.Tone.NEUTRAL,
                title = "「${repeated.key}」又出现了",
                detail = "近 ${repeated.value} 次计分或复做记录里都写了它。练前先回看，再开始计时。",
            )
        }

        // N7 只专注不练习：近 7 天有 ≥3 次专注、但没有一条练习/得分记录。
        val focusStart = today.minusDays(N7_DAYS - 1)
        fun localDate(session: Session): LocalDate = Instant.ofEpochMilli(
            if (attribution == DateAttribution.END) session.endAt ?: session.startAt else session.startAt,
        ).atZone(zoneId).toLocalDate()
        val recentFocus = SessionScopes.focus(sessions).filter { !localDate(it).isBefore(focusStart) }
        val recentOutput = sessions.filter {
            !SessionScopes.isFocus(it) && SessionScopes.isActivity(it) && !localDate(it).isBefore(focusStart)
        }
        if (recentFocus.size >= N7_MIN_FOCUS && recentOutput.isEmpty()) {
            found += Insight(
                id = "N7", tone = Insight.Tone.NEUTRAL,
                title = "近 $N7_DAYS 天只在专注，没有练习输出",
                detail = "有 ${recentFocus.size} 次专注，但没有可检验正确率或得分的记录。专注证明投入，不替代输出；安排一次 10 题或一次计分练习。",
            )
        }

        // 归档袋与滞留袋只作事实描述，不触发行动（R6 只登记不实装）。
        return found.sortedWith(compareBy({ it.tone.ordinal }, { it.id })).take(max)
    }

    /** N5 的客观题样本必须来自 performance 子集，绝不能把 SCORE/REDO 混进来。 */
    private fun objectiveAccuracy(sessions: List<Session>, categoryId: Long): Double? {
        val rows = SessionScopes.performance(sessions).filter { it.categoryId == categoryId }
        val total = rows.sumOf { it.totalCount }
        return if (total < com.tiji.studyclock.domain.stats.Thresholds.REFERENCE_QUESTIONS) null
        else rows.sumOf { it.correctCount }.toDouble() / total
    }

    /**
     * 科目覆盖：不依赖正确率，只回答“还有哪块没开始”。
     * 真实历史里 21 个类别有 19 个只练过 1 次，类别级趋势暂时不可用；覆盖是此时唯一稳定可给的行动。
     */
    private fun coverage(
        sessions: List<Session>,
        subjects: List<Subject>,
        exam: ExamTarget,
        today: LocalDate,
    ): Insight? {
        val examDate = exam.examDate ?: return null
        val days = ChronoUnit.DAYS.between(today, examDate)
        if (days < 0) return null
        val practiced = sessions.mapTo(mutableSetOf()) { it.subjectId }
        val untouched = subjects.filter { it.id !in practiced }
        if (untouched.size < COVERAGE_MIN_UNTOUCHED) return null
        val names = untouched.take(4).joinToString("、") { it.name } +
            if (untouched.size > 4) " 等 ${untouched.size} 个科目" else ""
        return Insight(
            id = "coverage",
            tone = Insight.Tone.ATTENTION,
            title = "距${exam.displayName} $days 天，${untouched.size} 个科目还是 0 题",
            detail = "$names 还没开始。先各做 10 题摸底，不用一次补完。",
        )
    }

    private fun gap(sessions: List<Session>, today: LocalDate, attribution: DateAttribution, streak: StreakSummary): Insight? {
        val last = streak.lastActive ?: return null
        val days = streak.daysSinceLast(today) ?: return null
        if (days < GAP_DAYS) return null
        val lastText = String.format(Locale.CHINA, "%d 月 %d 日", last.monthValue, last.dayOfMonth)
        return Insight(
            id = "gap",
            tone = Insight.Tone.ATTENTION,
            title = "已经 $days 天没有练习",
            detail = "上次是 $lastText。先做 10 题恢复手感，不用补上落下的量。",
        )
    }

    private fun streakInsight(streak: StreakSummary): Insight? {
        if (streak.current < STREAK_MIN) return null
        return Insight(
            id = "streak",
            tone = Insight.Tone.POSITIVE,
            title = "连续 ${streak.current} 天有练习",
            detail = "你的最长连击是 ${streak.longest} 天。稳定比加量更值钱。",
        )
    }

    private fun hourGap(hourStats: List<HourStats>): Insight? {
        val qualified = hourStats.filter { stats ->
            stats.accuracy.value != null && stats.accuracy.questionCount >= HOUR_MIN_QUESTIONS
        }
        if (qualified.size < 2) return null
        val best = qualified.maxByOrNull { it.accuracy.value!! } ?: return null
        val worst = qualified.minByOrNull { it.accuracy.value!! } ?: return null
        if (best.hour == worst.hour) return null
        val gap = best.accuracy.value!! - worst.accuracy.value!!
        if (gap < HOUR_MIN_GAP) return null
        return Insight(
            id = "hour",
            tone = Insight.Tone.NEUTRAL,
            title = "${best.hour} 点比 ${worst.hour} 点高 ${Math.round(gap * 100)} pt",
            detail = "两边各做了至少 $HOUR_MIN_QUESTIONS 题。新内容优先放在 ${best.hour} 点，机械记忆留给 ${worst.hour} 点。",
        )
    }

    private fun stagnation(sessions: List<Session>, categories: List<Category>): Insight? {
        val byCategory = sessions.groupBy { it.categoryId }
        var candidate: Pair<Category, Double>? = null
        var candidateSample: Triple<Int, Int, Int>? = null
        byCategory.forEach { (categoryId, rows) ->
            if (rows.size < STAGNATION_SESSIONS) return@forEach
            val ordered = rows.sortedBy { it.startAt }
            val recent = ordered.takeLast(3)
            val earlier = ordered.dropLast(3)
            // 只有 1 次基线时，所谓“此前”就是一次波动；不足以支撑趋势判断。
            if (earlier.size < 2) return@forEach
            val earlierQuestions = earlier.sumOf { it.totalCount }
            val recentQuestions = recent.sumOf { it.totalCount }
            if (earlierQuestions < STAGNATION_MIN_QUESTIONS || recentQuestions < STAGNATION_MIN_QUESTIONS) return@forEach
            fun accuracyOf(list: List<Session>): Double {
                val questions = list.sumOf { it.totalCount }
                return if (questions == 0) 0.0 else list.sumOf { it.correctCount }.toDouble() / questions
            }
            val drop = accuracyOf(earlier) - accuracyOf(recent)
            if (drop < STAGNATION_DROP) return@forEach
            val category = categories.find { it.id == categoryId } ?: return@forEach
            val current = candidate
            if (current == null || drop > current.second) {
                candidate = category to drop
                candidateSample = Triple(earlier.size, earlierQuestions, recentQuestions)
            }
        }
        val (category, drop) = candidate ?: return null
        val (earlierCount, earlierQuestions, recentQuestions) = candidateSample ?: return null
        val earlierAccuracy = sessions.filter { it.categoryId == category.id }
            .sortedBy { it.startAt }.dropLast(3)
        val recentAccuracy = sessions.filter { it.categoryId == category.id }
            .sortedBy { it.startAt }.takeLast(3)
        fun percent(list: List<Session>): String {
            val questions = list.sumOf { it.totalCount }
            val correct = list.sumOf { it.correctCount }
            return String.format(Locale.CHINA, "%.1f", if (questions == 0) 0.0 else correct * 100.0 / questions)
        }
        return Insight(
            id = "stagnation",
            tone = Insight.Tone.ATTENTION,
            title = "${category.name} 最近 3 次低于此前 ${Math.round(drop * 100)} pt",
            detail = "此前 $earlierCount 次 $earlierQuestions 题（${percent(earlierAccuracy)}%），最近 3 次 $recentQuestions 题（${percent(recentAccuracy)}%）。先回看错题，再刷新题。",
        )
    }

    private fun goal(sessions: List<Session>, today: LocalDate, attribution: DateAttribution, dailyGoal: Int?): Insight? {
        val goal = dailyGoal?.takeIf { it > 0 } ?: return null
        val start = today.minusDays(GOAL_WINDOW_DAYS - 1)
        val perDay = mutableMapOf<LocalDate, Int>()
        sessions.forEach { session ->
            val date = sessionDate(session, attribution)
            if (!date.isBefore(start) && !date.isAfter(today)) {
                perDay[date] = (perDay[date] ?: 0) + session.totalCount
            }
        }
        val hit = (0 until GOAL_WINDOW_DAYS).count { offset ->
            (perDay[today.minusDays(offset)] ?: 0) >= goal
        }
        return when {
            hit <= GOAL_LOW_DAYS -> Insight(
                id = "goal",
                tone = Insight.Tone.ATTENTION,
                title = "近 $GOAL_WINDOW_DAYS 天只有 $hit 天达到 $goal 题",
                detail = "目标可能定高了。改成“每天至少 10 题”先稳住，再往上加。",
            )
            hit >= GOAL_HIGH_DAYS -> Insight(
                id = "goal",
                tone = Insight.Tone.POSITIVE,
                title = "近 $GOAL_WINDOW_DAYS 天有 $hit 天达到 $goal 题",
                detail = "目标已经形成惯性，可以试着每天多加 5 题。",
            )
            else -> null
        }
    }

    /**
     * 错题关键词集中度：只统计近 14 天“确实做错了且写了关键词”的记录。
     * 样本太少或没有明显集中时保持沉默，避免把偶发标签说成规律。
     */
    private fun tagConcentration(sessions: List<Session>, today: LocalDate, attribution: DateAttribution): Insight? {
        val start = today.minusDays(TAG_WINDOW_DAYS - 1)
        val taggedWrong = sessions.filter { session ->
            session.totalCount > session.correctCount &&
                TagRules.parse(session.wrongTags).isNotEmpty() &&
                !sessionDate(session, attribution).isBefore(start) &&
                !sessionDate(session, attribution).isAfter(today)
        }
        if (taggedWrong.size < TAG_MIN_SESSIONS) return null
        val counts = mutableMapOf<String, Int>()
        taggedWrong.forEach { session ->
            TagRules.parse(session.wrongTags).forEach { tag ->
                counts[tag] = (counts[tag] ?: 0) + 1
            }
        }
        val (tag, count) = counts.entries.maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key }) ?: return null
        val share = count.toDouble() / taggedWrong.size
        if (count < TAG_MIN_SESSIONS || share < TAG_MIN_SHARE) return null
        return Insight(
            id = "tag",
            tone = Insight.Tone.ATTENTION,
            title = "错题集中在「$tag」",
            detail = "近 $TAG_WINDOW_DAYS 天 ${taggedWrong.size} 次写有关键词的错题里，有 $count 次提到它。下次练这类题前先回看，再开始计时。",
        )
    }
}
