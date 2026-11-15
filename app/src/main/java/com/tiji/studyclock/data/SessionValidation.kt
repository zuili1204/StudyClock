package com.tiji.studyclock.data

import com.tiji.studyclock.domain.model.RecordMode
import com.tiji.studyclock.domain.model.ScoreRules
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionKind
import com.tiji.studyclock.domain.model.TagRules

internal fun requireValidSessionFields(session: Session) {
    require(session.id >= 0) { "记录 ID 不能为负数" }
    require(session.categoryId > 0 && session.subjectId > 0) { "请选择有效科目和类别" }
    // v0.11：FOCUS 没有题量，因此题量约束按 mode 分支；其余模式仍要求正题量。
    if (session.mode == RecordMode.FOCUS) {
        require(session.totalCount == 0) { "专注记录不能填写题量" }
        require(session.correctCount == 0) { "专注记录不能填写答对数" }
        require(session.kind == SessionKind.NORMAL) { "专注记录不能是错题复做" }
    } else {
        require(session.totalCount > 0) { "题量必须大于 0" }
        require(session.correctCount in 0..session.totalCount) { "正确数必须在 0 到题量之间" }
    }
    require(session.durationSec == null || session.durationSec > 0) { "时长只能为空或正数" }
    if (session.mode == RecordMode.FOCUS) {
        require((session.durationSec ?: 0) > 0) { "专注记录必须填写时长" }
    }
    require(session.pausedSec >= 0 && session.pauseCount >= 0) { "暂停时长和次数不能为负数" }
    require(session.difficulty in 0..3 && session.energy in 0..3) { "自评值必须在 0..3" }
    require(session.endAt == null || session.endAt >= session.startAt) { "结束时间不能早于开始时间" }
    val tagProblem = TagRules.error(session.wrongTags)
    require(tagProblem == null) { tagProblem.orEmpty() }

    // v5：模式与性质互斥规则。SCORE 的 correctCount = 0 表示「不适用」，不是「全错」（D20）。
    when (session.mode) {
        RecordMode.COUNT -> require(
            session.scoreTenths == null && session.maxScoreTenths == null && session.scoreLabel == null,
        ) { "客观题记录不能带计分字段" }
        RecordMode.SCORE -> {
            ScoreRules.error(session.scoreTenths, session.maxScoreTenths, session.scoreLabel)
                ?.let { require(false) { it } }
            require(session.answerSequence == null) { "计分记录不能填写对错序列" }
            if (session.kind == SessionKind.NORMAL) {
                require(session.correctCount == 0) { "计分记录的答对数应为 0（不适用）" }
            }
        }
        RecordMode.FOCUS -> {
            require(
                session.scoreTenths == null && session.maxScoreTenths == null && session.scoreLabel == null,
            ) { "专注记录不能带计分字段" }
            require(session.answerSequence == null) { "专注记录不能填写对错序列" }
            require(session.wrongTags.isNullOrBlank()) { "专注记录不能填写错题关键词" }
        }
    }
    // CSV 不表达袋关联：导入的 REDO 行允许 batchId 为空，表示「待在应用内挂接」，
    // 这类记录不产生 ReviewAttempt，也不进错题袋消化率（导入报告会单独列出）。
    if (session.kind == SessionKind.REDO) {
        require(session.batchId == null || session.batchId > 0) { "复做记录的错题袋编号无效" }
    } else {
        require(session.batchId == null) { "普通记录不能关联错题袋" }
    }
}

/** Invalid optional sequences never prevent otherwise valid records from being saved. */
fun validateSession(session: Session, onInvalidSequence: (String) -> Unit = {}): Session {
    requireValidSessionFields(session)
    val sequence = session.answerSequence ?: return session
    val issue = when {
        sequence.length != session.totalCount -> "序列长度与题量不一致"
        sequence.any { it != '0' && it != '1' } -> "序列包含非 0/1 字符"
        sequence.count { it == '1' } != session.correctCount -> "序列正确数与记录不一致"
        else -> null
    }
    if (issue == null) return session
    onInvalidSequence(issue)
    return session.copy(answerSequence = null)
}
