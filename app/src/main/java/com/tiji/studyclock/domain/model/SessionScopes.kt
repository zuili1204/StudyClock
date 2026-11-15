package com.tiji.studyclock.domain.model

/**
 * **统一过滤层**：所有统计/诊断/处方引擎只能通过这里取样本，防止口径漂移（质量闸 F）。
 *
 * - activity（投入面）：题量 > 0 或 FOCUS，题量、净时长、时段、热力、连击、日历都用这一份；
 * - performance（正确率轨）：仅 COUNT + NORMAL，所有正确率指标；
 * - scored（得分轨）：仅 SCORE，得分率与均分；
 * - redo（错题复做轨）：仅 REDO，通过率与消化率；
 * - focus：仅 FOCUS，只贡献时长。
 *
 * 三轨都是 0–1 的比例，但**禁止互相平均或合并**；REDO 计入投入面但不进 performance；
 * FOCUS 计入投入面，但不进任何表现轨（D28）。
 * UI 为展示目的读取 mode/kind 不受此限，UI 不得自行聚合指标。
 */
object SessionScopes {
    /** 三个互斥象限的单点判定，供 X1 结构占比与 UI 标签复用。 */
    fun isRedo(session: Session): Boolean = session.kind == SessionKind.REDO

    fun isScored(session: Session): Boolean = session.mode == RecordMode.SCORE

    fun isFocus(session: Session): Boolean = session.mode == RecordMode.FOCUS

    fun isObjective(session: Session): Boolean = !isRedo(session) && !isScored(session) && !isFocus(session)

    /** 单点判定：其它模块需要逐条判断时只允许调用它，不自行比较 mode/kind。 */
    fun isPerformance(session: Session): Boolean =
        session.mode == RecordMode.COUNT && session.kind == SessionKind.NORMAL &&
            session.totalCount > 0 && session.correctCount in 0..session.totalCount

    /** 投入面：有题量的练习，或一段有效的专注时长。 */
    fun isActivity(session: Session): Boolean = when {
        isFocus(session) -> (session.durationSec ?: 0) > 0
        else -> session.totalCount > 0 && session.correctCount in 0..session.totalCount
    }

    fun activity(sessions: List<Session>): List<Session> =
        sessions.filter(::isActivity)

    /** 专注只贡献时长；没有时长的 FOCUS 记录不进任何口径。 */
    fun focus(sessions: List<Session>): List<Session> =
        sessions.filter { isFocus(it) && (it.durationSec ?: 0) > 0 }

    fun performance(sessions: List<Session>): List<Session> =
        sessions.filter(::isPerformance)

    /** 单点判定：这条记录是否构成得分轨的一个有效样本。 */
    fun isScoredRated(session: Session): Boolean =
        session.mode == RecordMode.SCORE && session.scoreTenths != null && session.maxScoreTenths != null &&
            session.maxScoreTenths > 0 && session.scoreTenths in 0..session.maxScoreTenths

    fun scored(sessions: List<Session>): List<Session> = sessions.filter(::isScoredRated)

    /** 单点判定：这条记录是否属于复做轨（有题量才有意义）。 */
    fun isRedoRated(session: Session): Boolean = session.kind == SessionKind.REDO && session.totalCount > 0

    /**
     * 单点判定：复做轨里「能判对错」的样本。SCORE 复做只有篇数与得分，`correctCount` 是
     * 「不适用」的 0（D20），不能拿来算通过率。
     */
    fun isRedoGraded(session: Session): Boolean =
        session.kind == SessionKind.REDO && session.mode == RecordMode.COUNT && session.totalCount > 0

    fun redo(sessions: List<Session>): List<Session> = sessions.filter(::isRedoRated)

    /** 词云的客观题错因样本：COUNT + NORMAL 且确实做错，且写了关键词。 */
    fun objectiveWrong(sessions: List<Session>): List<Session> =
        performance(sessions).filter { it.totalCount > it.correctCount && !it.wrongTags.isNullOrBlank() }

    /** 计分/复做记录的关键词单独成组，不混进客观题词云。 */
    fun otherTagged(sessions: List<Session>): List<Session> =
        sessions.filter { (it.mode == RecordMode.SCORE || it.kind == SessionKind.REDO) && !it.wrongTags.isNullOrBlank() }

    /** 处方效果验证样本：沿用 COUNT+NORMAL，显式排除 REDO（D19/D26）。 */
    fun prescriptionSample(sessions: List<Session>): List<Session> = performance(sessions)
}
