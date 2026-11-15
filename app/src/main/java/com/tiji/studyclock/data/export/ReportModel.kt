package com.tiji.studyclock.data.export

import com.tiji.studyclock.domain.model.Category
import com.tiji.studyclock.domain.model.RecordMode
import com.tiji.studyclock.domain.model.ScoreRules
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionKind
import com.tiji.studyclock.domain.model.SessionSource
import com.tiji.studyclock.domain.model.Subject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 报表模型：只承载已经算好的展示值，writer 不做任何算术。
 * 与《软件设计书》§8.2 的 14 个物理列一一对应，顺序固定。
 */
data class ReportRow(
    val date: String,
    val subject: String,
    val category: String,
    val questions: Int,
    val correct: Int,
    val accuracy: String,
    val timeRange: String,
    val durationText: String,
    val durationSeconds: String,
    val secondsPerQuestion: String,
    val answerSequence: String,
    val difficulty: String,
    val energy: String,
    /** 错题关键词；v4 起追加，旧文件缺这一列也能导入。 */
    val wrongTags: String,
    // ---- v5 追加 6 列（1–14 顺序与列名完全不变） ----
    val mode: String = "客观题",
    val kind: String = "普通",
    val scoreLabel: String = "",
    val score: String = "",
    val maxScore: String = "",
    val scoreRate: String = "",
    /** SCORE 行的正确数语义为「不适用」，第 5 列留空，不能写成 0。 */
    val correctDisplay: String = correct.toString(),
    /** FOCUS 行没有题量，第 4 列同样留空；`questions` 仍保留 0 供内部求和。 */
    val questionsDisplay: String = questions.toString(),
)

data class ReportModel(val rows: List<ReportRow>)

object ReportBuilder {
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA)
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)

    fun build(
        sessions: List<Session>,
        subjects: List<Subject>,
        categories: List<Category>,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): ReportModel {
        val subjectNames = subjects.associate { it.id to it.name }
        val categoryNames = categories.associate { it.id to it.name }
        val rows = sessions.sortedWith(compareBy({ it.startAt }, { it.id })).map { session ->
            val start = Instant.ofEpochMilli(session.startAt).atZone(zoneId)
            val duration = session.durationSec
            val isScore = session.mode == RecordMode.SCORE
            val isFocus = session.mode == RecordMode.FOCUS
            val isRedo = session.kind == SessionKind.REDO
            val rate = ScoreRules.rate(session.scoreTenths, session.maxScoreTenths)
            ReportRow(
                date = start.format(dateFormat),
                subject = subjectNames[session.subjectId].orEmpty(),
                category = categoryNames[session.categoryId].orEmpty(),
                questions = session.totalCount,
                correct = session.correctCount,
                // SCORE/FOCUS 的 correctCount=0 表示「不适用」，REDO 的通过率不属于主正确率：都留空。
                accuracy = if (isScore || isFocus || isRedo) "" else {
                    String.format(Locale.CHINA, "%.1f%%", session.correctCount * 100.0 / session.totalCount)
                },
                timeRange = timeRange(session, zoneId),
                durationText = duration?.let { String.format(Locale.CHINA, "%d:%02d", it / 60, it % 60) }.orEmpty(),
                durationSeconds = duration?.toString().orEmpty(),
                secondsPerQuestion = duration?.takeIf { session.totalCount > 0 }?.let {
                    String.format(Locale.CHINA, "%.1f", it.toDouble() / session.totalCount)
                }.orEmpty(),
                answerSequence = session.answerSequence.orEmpty(),
                difficulty = DIFFICULTY.getOrElse(session.difficulty) { "" },
                energy = ENERGY.getOrElse(session.energy) { "" },
                wrongTags = session.wrongTags.orEmpty(),
                mode = when {
                    isFocus -> "专注"
                    isScore -> "计分"
                    else -> "客观题"
                },
                kind = if (isRedo) "复做" else "普通",
                scoreLabel = session.scoreLabel.orEmpty(),
                score = session.scoreTenths?.let(ScoreRules::format).orEmpty(),
                maxScore = session.maxScoreTenths?.let(ScoreRules::format).orEmpty(),
                scoreRate = rate?.let { String.format(Locale.CHINA, "%.1f%%", it * 100) }.orEmpty(),
                correctDisplay = if (isScore || isFocus) "" else session.correctCount.toString(),
                questionsDisplay = if (isFocus) "" else session.totalCount.toString(),
            )
        }
        return ReportModel(rows)
    }

    /** 没有真实起止就留空，避免把日期锚点当成练习时间；补记填了时刻则照常写出。 */
    private fun timeRange(session: Session, zoneId: ZoneId): String {
        val end = session.endAt ?: return ""
        if (end <= session.startAt) return ""
        val start = Instant.ofEpochMilli(session.startAt).atZone(zoneId)
        val endZ = Instant.ofEpochMilli(end).atZone(zoneId)
        val endLabel = if (endZ.toLocalDate() == start.toLocalDate()) {
            endZ.format(timeFormat)
        } else {
            String.format(Locale.CHINA, "%02d:%02d", endZ.hour + 24 * (endZ.toLocalDate().toEpochDay() - start.toLocalDate().toEpochDay()).toInt(), endZ.minute)
        }
        return "${start.format(timeFormat)}-$endLabel"
    }

    private val DIFFICULTY = listOf("", "易", "中", "难")
    private val ENERGY = listOf("", "低", "中", "高")
}
