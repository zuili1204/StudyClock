package com.tiji.studyclock.domain.stats

import com.tiji.studyclock.domain.model.Category
import com.tiji.studyclock.domain.model.ReviewAttempt
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionScopes
import com.tiji.studyclock.domain.model.TagRules
import com.tiji.studyclock.domain.model.WrongBatch
import java.time.LocalDate

/** X1：四个互斥象限的投入结构，只描述事实，不合并成综合分。 */
enum class ModeBucket { OBJECTIVE, SCORED, REDO, FOCUS }

data class ModeShare(
    val bucket: ModeBucket,
    val sessions: Int,
    val questions: Int,
    val timedSeconds: Long,
    val questionShare: Double,
    val durationShare: Double,
)

data class ModeStructure(
    val totalSessions: Int,
    val totalQuestions: Int,
    val totalSeconds: Long,
    val shares: List<ModeShare>,
)

/** X2：同类别三轨并列；任一轨样本不足则该轨为 null，绝不补齐或平均。 */
data class TrackComparison(
    val categoryId: Long,
    val categoryName: String,
    /** COUNT + NORMAL 的加权正确率，仅 ≥REFERENCE 才给值。 */
    val objectiveAccuracy: Double?,
    /** SCORE 的加权得分率，仅 ≥REFERENCE 才给值。 */
    val scoreRate: Double?,
    /** REDO 的累计通过率，仅 ≥REFERENCE 才给值。 */
    val redoPassRate: Double?,
    /** v0.11：同期专注秒数，只作上下文，不参与任何比例计算。 */
    val focusSeconds: Long = 0,
)

/** X3：错因 × 类别 × 三轨并列，只给可核实事实，不推断因果。 */
data class TagCrossRow(
    val tag: String,
    val categoryId: Long,
    val objectiveQuestions: Int,
    val objectiveAccuracy: Double?,
    val scoredSessions: Int,
    val scoredRate: Double?,
    val redoAttempted: Int,
    val redoPassRate: Double?,
)

object CrossModeStats {
    private fun bucket(session: Session): ModeBucket = when {
        SessionScopes.isFocus(session) -> ModeBucket.FOCUS
        SessionScopes.isRedo(session) -> ModeBucket.REDO
        SessionScopes.isScored(session) -> ModeBucket.SCORED
        else -> ModeBucket.OBJECTIVE
    }

    /** X1：题量、时长、次数占比；四象限互斥，合计恒等于全部会话。 */
    fun structure(sessions: List<Session>): ModeStructure {
        val all = SessionScopes.activity(sessions)
        val totalQuestions = all.sumOf { it.totalCount }
        val totalSeconds = all.sumOf { (it.durationSec ?: 0).toLong() }
        val shares = ModeBucket.entries.map { target ->
            val rows = all.filter { bucket(it) == target }
            val questions = rows.sumOf { it.totalCount }
            val seconds = rows.sumOf { (it.durationSec ?: 0).toLong() }
            ModeShare(
                bucket = target,
                sessions = rows.size,
                questions = questions,
                timedSeconds = seconds,
                questionShare = if (totalQuestions == 0) 0.0 else questions.toDouble() / totalQuestions,
                durationShare = if (totalSeconds == 0L) 0.0 else seconds.toDouble() / totalSeconds,
            )
        }
        return ModeStructure(all.size, totalQuestions, totalSeconds, shares)
    }

    /** X2：只并列，不合并；两轨各 ≥REFERENCE 才同时显示。 */
    fun compareByCategory(
        sessions: List<Session>,
        categories: List<Category>,
        countSnapshot: StatsSnapshot,
        scoreSnapshot: ScoreSnapshot,
        batches: List<WrongBatch>,
        attempts: List<ReviewAttempt>,
        today: LocalDate,
    ): List<TrackComparison> {
        val names = categories.associate { it.id to it.name }
        val redoByCategory = redoRateByCategory(sessions, batches, attempts, today)
        val focusByCategory = SessionScopes.focus(sessions).groupBy { it.categoryId }
            .mapValues { (_, rows) -> rows.sumOf { (it.durationSec ?: 0).toLong() } }
        val ids = (countSnapshot.categoryStats.map { it.categoryId } +
            scoreSnapshot.byCategory.keys + redoByCategory.keys + focusByCategory.keys).distinct().sorted()
        return ids.map { id ->
            TrackComparison(
                categoryId = id,
                categoryName = names[id] ?: "未分类",
                objectiveAccuracy = qualified(countSnapshot.categoryStats.firstOrNull { it.categoryId == id }?.accuracy),
                scoreRate = qualified(scoreSnapshot.byCategory[id]?.rate),
                redoPassRate = redoByCategory[id]?.second,
                focusSeconds = focusByCategory[id] ?: 0,
            )
        }.filter {
            it.objectiveAccuracy != null || it.scoreRate != null || it.redoPassRate != null || it.focusSeconds > 0
        }
    }

    /** X3：关键词分组后三轨并列，样本不足的轨留 null，不做因果推断。 */
    fun tagCross(
        sessions: List<Session>,
        batches: List<WrongBatch>,
        attempts: List<ReviewAttempt>,
        today: LocalDate,
    ): List<TagCrossRow> {
        val byTag = mutableMapOf<Pair<String, Long>, MutableList<Session>>()
        SessionScopes.activity(sessions).forEach { session ->
            TagRules.parse(session.wrongTags).forEach { tag ->
                byTag.getOrPut(tag to session.categoryId) { mutableListOf() }.add(session)
            }
        }
        val redoRows = RedoLedger.ledgers(batches, attempts, today)
        return byTag.map { (key, rows) ->
            val objective = SessionScopes.performance(rows)
            val scored = SessionScopes.scored(rows)
            val redo = SessionScopes.redo(rows)
            val redoAttempted = redo.sumOf { it.totalCount }
            val redoPassed = redo.sumOf { it.correctCount }
            val bagIds = batches.filter { it.categoryId == key.second &&
                TagRules.parse(it.wrongTags).contains(key.first) }.map { it.id }.toSet()
            val linked = redoRows.filter { it.batch.id in bagIds }
            TagCrossRow(
                tag = key.first,
                categoryId = key.second,
                objectiveQuestions = objective.sumOf { it.totalCount },
                objectiveAccuracy = if (objective.isEmpty()) null else
                    objective.sumOf { it.correctCount }.toDouble() / objective.sumOf { it.totalCount },
                scoredSessions = scored.size,
                scoredRate = if (scored.isEmpty()) null else
                    scored.sumOf { it.scoreTenths!!.toLong() }.toDouble() / scored.sumOf { it.maxScoreTenths!!.toLong() },
                redoAttempted = redoAttempted + linked.sumOf { it.attemptedTotal },
                redoPassRate = run {
                    val attempted = redoAttempted + linked.sumOf { it.attemptedTotal }
                    val passed = redoPassed + linked.sumOf { it.passedTotal }
                    if (attempted == 0) null else passed.toDouble() / attempted
                },
            )
        }.sortedWith(compareByDescending<TagCrossRow> { it.objectiveQuestions }.thenBy { it.tag })
    }

    private fun qualified(metric: Metric<Double>?): Double? =
        metric?.takeIf { it.level != ConfidenceLevel.INSUFFICIENT }?.value

    private fun redoRateByCategory(
        sessions: List<Session>,
        batches: List<WrongBatch>,
        attempts: List<ReviewAttempt>,
        today: LocalDate,
    ): Map<Long, Pair<Int, Double?>> {
        val rows = RedoLedger.ledgers(batches, attempts, today)
        val sessionRedo = SessionScopes.redo(sessions).groupBy { it.categoryId }
        return (rows.map { it.batch.categoryId } + sessionRedo.keys).distinct().associateWith { id ->
            val linked = rows.filter { it.batch.categoryId == id }
            val attempted = linked.sumOf { it.attemptedTotal } +
                sessionRedo[id].orEmpty().sumOf { it.totalCount }
            val passed = linked.sumOf { it.passedTotal } +
                sessionRedo[id].orEmpty().sumOf { it.correctCount }
            val bags = linked.size
            val rate = if (attempted == 0) null else passed.toDouble() / attempted
            // REDO 轨 ≥REFERENCE 才给值：至少 2 袋且 2 轮。
            bags to (if (bags >= Thresholds.REFERENCE_SESSIONS && linked.sumOf { it.rounds } >= Thresholds.REFERENCE_SESSIONS) rate else null)
        }
    }
}
