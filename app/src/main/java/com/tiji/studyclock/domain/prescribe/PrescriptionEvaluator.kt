package com.tiji.studyclock.domain.prescribe

import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionScopes
import com.tiji.studyclock.domain.model.Verdict
import java.util.Locale

/**
 * 处方效果验证：处方开始前最近 3 次 vs 完成后最先 3 次独立记录。
 * 执行期记录不混入后测；前后各 ≥3 次且各 ≥32 题才给结论，否则如实返回不足。
 */
data class PrescriptionEvaluation(
    val verdict: Verdict,
    val beforeAccuracy: Double?,
    val afterAccuracy: Double?,
    val beforeSecPerQ: Double?,
    val afterSecPerQ: Double?,
    val beforeQuestions: Int,
    val afterQuestions: Int,
    val beforeSessionIds: List<Long>,
    val afterSessionIds: List<Long>,
    val calibrated: Boolean,
    val note: String,
)

object PrescriptionEvaluator {
    const val VERIFY_SESSIONS = 3
    const val VERIFY_QUESTIONS = 32
    const val EFFECT = 0.05
    const val DIFFICULTY_STEP = 1.0
    const val CALIBRATION_OFFSET = 0.05

    fun evaluate(
        sessions: List<Session>,
        categoryId: Long,
        startedAt: Long,
        completedAt: Long,
    ): PrescriptionEvaluation {
        // v5（D19/D26）：验证样本只取 COUNT + NORMAL，显式排除 SCORE 与 REDO，
        // 否则「已见过的题」会把后测正确率抬高，前后测不再可比。
        val valid = SessionScopes.prescriptionSample(sessions)
            .filter { it.categoryId == categoryId }
            .sortedWith(compareBy<Session> { it.startAt }.thenBy { it.id })
        val before = valid.filter { it.startAt < startedAt }.takeLast(VERIFY_SESSIONS)
        // 完成后才计入验证；执行期间的记录既不当前测，也不当后测。
        val after = valid.filter { it.startAt > completedAt }.take(VERIFY_SESSIONS)

        val beforeQuestions = before.sumOf { it.totalCount }
        val afterQuestions = after.sumOf { it.totalCount }
        val beforeAccuracy = accuracy(before)
        val afterAccuracy = accuracy(after)
        val beforeSeconds = secondsPerQuestion(before)
        val afterSeconds = secondsPerQuestion(after)
        val rawDelta = if (beforeAccuracy != null && afterAccuracy != null) afterAccuracy - beforeAccuracy else null
        val calibrated = rawDelta != null && difficultyRaised(before, after)
        val delta = rawDelta?.let { if (calibrated) it + CALIBRATION_OFFSET else it }

        val enough = before.size >= VERIFY_SESSIONS && beforeQuestions >= VERIFY_QUESTIONS &&
            after.size >= VERIFY_SESSIONS && afterQuestions >= VERIFY_QUESTIONS
        val verdict = when {
            !enough || delta == null -> Verdict.INSUFFICIENT
            delta >= EFFECT -> Verdict.EFFECTIVE
            delta <= -EFFECT -> Verdict.INEFFECTIVE
            else -> Verdict.NEUTRAL
        }
        return PrescriptionEvaluation(
            verdict = verdict,
            beforeAccuracy = beforeAccuracy,
            afterAccuracy = afterAccuracy,
            beforeSecPerQ = beforeSeconds,
            afterSecPerQ = afterSeconds,
            beforeQuestions = beforeQuestions,
            afterQuestions = afterQuestions,
            beforeSessionIds = before.map { it.id },
            afterSessionIds = after.map { it.id },
            calibrated = calibrated,
            note = note(verdict, before, beforeQuestions, after, afterQuestions, rawDelta, delta, calibrated),
        )
    }

    /** 只有前后各 ≥3 条非空自评、且后测平均难度至少高 1 档时才加 5pt；这是启发式，不是因果证明。 */
    private fun difficultyRaised(before: List<Session>, after: List<Session>): Boolean {
        val beforeRatings = before.map { it.difficulty }.filter { it > 0 }
        val afterRatings = after.map { it.difficulty }.filter { it > 0 }
        if (beforeRatings.size < VERIFY_SESSIONS || afterRatings.size < VERIFY_SESSIONS) return false
        return afterRatings.average() >= beforeRatings.average() + DIFFICULTY_STEP
    }

    private fun accuracy(rows: List<Session>): Double? {
        val questions = rows.sumOf { it.totalCount }
        if (questions == 0) return null
        return rows.sumOf { it.correctCount }.toDouble() / questions
    }

    private fun secondsPerQuestion(rows: List<Session>): Double? {
        val timed = rows.filter { (it.durationSec ?: 0) > 0 }
        val questions = timed.sumOf { it.totalCount }
        if (questions == 0) return null
        return timed.sumOf { it.durationSec!!.toLong() }.toDouble() / questions
    }

    private fun note(
        verdict: Verdict,
        before: List<Session>,
        beforeQuestions: Int,
        after: List<Session>,
        afterQuestions: Int,
        rawDelta: Double?,
        delta: Double?,
        calibrated: Boolean,
    ): String {
        if (verdict == Verdict.INSUFFICIENT) {
            val pieces = mutableListOf<String>()
            if (before.size < VERIFY_SESSIONS) pieces += "前测 ${before.size} 次，还差 ${VERIFY_SESSIONS - before.size} 次"
            if (beforeQuestions < VERIFY_QUESTIONS) pieces += "前测 $beforeQuestions 题，还差 ${VERIFY_QUESTIONS - beforeQuestions} 题"
            if (after.size < VERIFY_SESSIONS) pieces += "后测 ${after.size} 次，还差 ${VERIFY_SESSIONS - after.size} 次"
            if (afterQuestions < VERIFY_QUESTIONS) pieces += "后测 $afterQuestions 题，还差 ${VERIFY_QUESTIONS - afterQuestions} 题"
            return "验证需要前后各 $VERIFY_SESSIONS 次、各 $VERIFY_QUESTIONS 题；" + pieces.joinToString("；") + "。"
        }
        val change = if (rawDelta != null && delta != null) {
            val raw = points(rawDelta)
            if (calibrated) {
                "原始变化 $raw；后测平均难度至少高 1 档，按 +5 pt 校准后为 ${points(delta)}。"
            } else {
                "变化 $raw。"
            }
        } else {
            "暂时无法计算变化。"
        }
        return "前后各 ${before.size} 次 $beforeQuestions 题；加权正确率 ${percent(accuracy(before))} → " +
            "${percent(accuracy(after))}，$change"
    }

    private fun percent(value: Double?): String =
        value?.let { String.format(Locale.CHINA, "%.1f%%", it * 100) } ?: "—"

    private fun points(value: Double): String {
        val sign = if (value > 0) "+" else ""
        return String.format(Locale.CHINA, "%s%.1f pt", sign, value * 100)
    }
}
