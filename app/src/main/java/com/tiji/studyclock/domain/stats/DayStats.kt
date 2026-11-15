package com.tiji.studyclock.domain.stats

import com.tiji.studyclock.domain.model.DateAttribution
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionScopes
import java.time.LocalDate
import java.time.YearMonth

/**
 * 当天出现过的记录类型，顺序即月历方格底部四个点的顺序。
 * 只表示「这天做了哪一类」，不代表做得好不好，也不合并成任何综合值。
 */
enum class DayTrack(val label: String, val short: String) {
    OBJECTIVE("正确率记录", "题"),
    SCORED("得分记录", "分"),
    REDO("错题复做", "复"),
    FOCUS("专注", "专"),
}

/**
 * 一天的结果汇总：题量、答对、净时长；正确率为派生值，无客观题样本时为 null。
 * v5：[questions] 是投入面（全部会话），[accuracy] 只用 performance（COUNT + NORMAL）子集，
 * 避免 SCORE 的 correctCount=0 或 REDO 的通过数把当天正确率拉偏。
 * v0.13：补齐「当天由什么构成」的分轨计数，供月历节律图与当天面板使用；仍然只做求和与计数，
 * 不生成任何跨轨综合值。
 */
data class DaySummary(
    val date: LocalDate,
    val sessionCount: Int = 0,
    val questions: Int = 0,
    val correct: Int = 0,
    val timedSeconds: Long = 0,
    val timedSessions: Int = 0,
    /** 参与正确率计算的客观题题量；无客观题时为 0，此时 accuracy 显示「—」。 */
    val performanceQuestions: Int = 0,
    /** v0.11：当天专注净时长；只作投入展示，不参与正确率。 */
    val focusSeconds: Long = 0,
    /** 当天客观题（COUNT + NORMAL）记录数。 */
    val objectiveSessions: Int = 0,
    /** 当天有效得分记录数与 Σ得分 / Σ满分（0.1 分单位）。 */
    val scoredSessions: Int = 0,
    val scoredTenths: Int = 0,
    val scoredMaxTenths: Int = 0,
    /** 当天复做记录数；通过率只取 COUNT 复做的题量与答对。 */
    val redoSessions: Int = 0,
    val redoQuestions: Int = 0,
    val redoCorrect: Int = 0,
    /** 当天专注记录数与按处方执行的记录数。 */
    val focusSessions: Int = 0,
    val prescriptionSessions: Int = 0,
) {
    val accuracy: Double? get() = if (performanceQuestions > 0) correct.toDouble() / performanceQuestions else null
    val scoreRate: Double? get() = if (scoredMaxTenths > 0) scoredTenths.toDouble() / scoredMaxTenths else null
    val redoRate: Double? get() = if (redoQuestions > 0) redoCorrect.toDouble() / redoQuestions else null
    val hasRecords: Boolean get() = sessionCount > 0

    fun count(track: DayTrack): Int = when (track) {
        DayTrack.OBJECTIVE -> objectiveSessions
        DayTrack.SCORED -> scoredSessions
        DayTrack.REDO -> redoSessions
        DayTrack.FOCUS -> focusSessions
    }

    fun has(track: DayTrack): Boolean = count(track) > 0
}

/** 一个月的结果汇总；[activeDays] 只数有记录的日子。 */
data class MonthSummary(
    val month: YearMonth,
    val activeDays: Int,
    val questions: Int,
    val correct: Int,
    val timedSeconds: Long,
    val timedSessions: Int,
    val performanceQuestions: Int = 0,
    val focusSeconds: Long = 0,
    val objectiveSessions: Int = 0,
    val scoredSessions: Int = 0,
    val scoredTenths: Int = 0,
    val scoredMaxTenths: Int = 0,
    val redoSessions: Int = 0,
    val redoQuestions: Int = 0,
    val redoCorrect: Int = 0,
    val focusSessions: Int = 0,
    val prescriptionSessions: Int = 0,
) {
    val accuracy: Double? get() = if (performanceQuestions > 0) correct.toDouble() / performanceQuestions else null
    val scoreRate: Double? get() = if (scoredMaxTenths > 0) scoredTenths.toDouble() / scoredMaxTenths else null
    val redoRate: Double? get() = if (redoQuestions > 0) redoCorrect.toDouble() / redoQuestions else null
    // 只有专注记录的月份也算有记录；不能因为题量为 0 把整月判成空。
    val hasRecords: Boolean get() = activeDays > 0
}

/**
 * 日历口径的唯一实现：月历、日期详情、今日卡都从这里取数，
 * 避免界面上分别求和导致“题量对得上、正确率对不上”。
 */
object DayStats {
    fun summarize(sessions: List<Session>, date: LocalDate, attribution: DateAttribution): DaySummary {
        val rows = sessions.filter { sessionDate(it, attribution) == date }
        return accumulate(date, rows)
    }

    fun byDay(sessions: List<Session>, month: YearMonth, attribution: DateAttribution): Map<LocalDate, DaySummary> {
        val result = mutableMapOf<LocalDate, Accumulator>()
        sessions.forEach { session ->
            val date = sessionDate(session, attribution)
            if (YearMonth.from(date) != month) return@forEach
            result.getOrPut(date) { Accumulator(date) }.add(session)
        }
        return result.mapValues { (_, value) -> value.toSummary() }
    }

    fun month(sessions: List<Session>, month: YearMonth, attribution: DateAttribution): MonthSummary {
        val days = byDay(sessions, month, attribution).values
        return MonthSummary(
            month = month,
            activeDays = days.size,
            questions = days.sumOf { it.questions },
            correct = days.sumOf { it.correct },
            timedSeconds = days.sumOf { it.timedSeconds },
            timedSessions = days.sumOf { it.timedSessions },
            performanceQuestions = days.sumOf { it.performanceQuestions },
            focusSeconds = days.sumOf { it.focusSeconds },
            objectiveSessions = days.sumOf { it.objectiveSessions },
            scoredSessions = days.sumOf { it.scoredSessions },
            scoredTenths = days.sumOf { it.scoredTenths },
            scoredMaxTenths = days.sumOf { it.scoredMaxTenths },
            redoSessions = days.sumOf { it.redoSessions },
            redoQuestions = days.sumOf { it.redoQuestions },
            redoCorrect = days.sumOf { it.redoCorrect },
            focusSessions = days.sumOf { it.focusSessions },
            prescriptionSessions = days.sumOf { it.prescriptionSessions },
        )
    }

    private fun accumulate(date: LocalDate, rows: List<Session>): DaySummary {
        val accumulator = Accumulator(date)
        rows.forEach(accumulator::add)
        return accumulator.toSummary()
    }

    private class Accumulator(private val date: LocalDate) {
        var sessions = 0
        var questions = 0
        var correct = 0
        var performanceQuestions = 0
        var objectiveSessions = 0
        var scoredSessions = 0
        var scoredTenths = 0
        var scoredMaxTenths = 0
        var redoSessions = 0
        var redoQuestions = 0
        var redoCorrect = 0
        var focusSessions = 0
        var prescriptionSessions = 0
        var seconds = 0L
        var timed = 0
        var focusSeconds = 0L

        fun add(session: Session) {
            sessions++
            questions += session.totalCount
            if (session.prescriptionId != null) prescriptionSessions++
            if (SessionScopes.isFocus(session)) {
                // 没有时长的专注记录是脏数据，任何口径都看不到它（与 SessionScopes.focus 一致）。
                val focusDuration = session.durationSec ?: 0
                if (focusDuration > 0) {
                    focusSessions++
                    focusSeconds += focusDuration.toLong()
                }
            }
            // 题量计入投入面，答对数只计入客观题表现面。
            if (SessionScopes.isPerformance(session)) {
                objectiveSessions++
                correct += session.correctCount
                performanceQuestions += session.totalCount
            }
            // 得分轨与复做轨各自独立取样，互不平均；同一条「计分复做」两轨都能看到。
            if (SessionScopes.isScoredRated(session)) {
                scoredSessions++
                scoredTenths += session.scoreTenths ?: 0
                scoredMaxTenths += session.maxScoreTenths ?: 0
            }
            if (SessionScopes.isRedoRated(session)) {
                redoSessions++
                if (SessionScopes.isRedoGraded(session)) {
                    redoQuestions += session.totalCount
                    redoCorrect += session.correctCount
                }
            }
            session.durationSec?.takeIf { it > 0 }?.let {
                seconds += it
                timed++
            }
        }

        fun toSummary() = DaySummary(
            date = date,
            sessionCount = sessions,
            questions = questions,
            correct = correct,
            timedSeconds = seconds,
            timedSessions = timed,
            performanceQuestions = performanceQuestions,
            focusSeconds = focusSeconds,
            objectiveSessions = objectiveSessions,
            scoredSessions = scoredSessions,
            scoredTenths = scoredTenths,
            scoredMaxTenths = scoredMaxTenths,
            redoSessions = redoSessions,
            redoQuestions = redoQuestions,
            redoCorrect = redoCorrect,
            focusSessions = focusSessions,
            prescriptionSessions = prescriptionSessions,
        )
    }
}
