package com.tiji.studyclock.domain.stats

import com.tiji.studyclock.domain.model.DateAttribution
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionScopes
import com.tiji.studyclock.domain.model.TagRules
import java.time.LocalDate

/**
 * 一个错题关键词的聚合结果。一次记录可以带多个关键词，因此“题量/错题数”会按出现次数重复计入；
 * [sessionCount] 才是真实出现次数，词云大小以它为准。
 */
data class TagStat(
    val tag: String,
    val sessionCount: Int,
    val questionCount: Int,
    val wrongCount: Int,
    val netSeconds: Long,
    val categoryIds: List<Long>,
    val sessionIds: List<Long>,
    val lastUsed: LocalDate,
) {
    /** 只在这批记录内部计算的正确率，便于和类别基线对照。 */
    val accuracy: Double? get() = if (questionCount > 0) (questionCount - wrongCount).toDouble() / questionCount else null
}

object TagStats {
    /**
     * 按关键词聚合。默认统计全部历史；传入 [since]/[until] 可只看一段窗口。
     * 输出按出现次数、错题数、最近使用排序，保证词云与列表顺序稳定。
     */
    fun summarize(
        sessions: List<Session>,
        attribution: DateAttribution,
        since: LocalDate? = null,
        until: LocalDate? = null,
    ): List<TagStat> {
        val buckets = linkedMapOf<String, Bucket>()
        sessions.forEach { session ->
            val tags = TagRules.parse(session.wrongTags)
            if (tags.isEmpty()) return@forEach
            val date = sessionDate(session, attribution)
            if (since != null && date.isBefore(since)) return@forEach
            if (until != null && date.isAfter(until)) return@forEach
            tags.forEach { tag ->
                buckets.getOrPut(tag) { Bucket(tag) }.add(session, date)
            }
        }
        return buckets.values
            .map { it.toStat() }
            .sortedWith(
                compareByDescending<TagStat> { it.sessionCount }
                    .thenByDescending { it.wrongCount }
                    .thenByDescending { it.lastUsed }
                    .thenBy { it.tag },
            )
    }

    /** v5 客观题词云：只吃 COUNT + NORMAL 且确实做错、并写了关键词的记录。 */
    fun summarizeObjective(
        sessions: List<Session>,
        attribution: DateAttribution,
        since: LocalDate? = null,
        until: LocalDate? = null,
    ): List<TagStat> = summarize(SessionScopes.objectiveWrong(sessions), attribution, since, until)

    /** v5 计分/复做关键词单独成组，不混进客观题词云，也不推断因果。 */
    fun summarizeOther(
        sessions: List<Session>,
        attribution: DateAttribution,
        since: LocalDate? = null,
        until: LocalDate? = null,
    ): List<TagStat> = summarize(SessionScopes.otherTagged(sessions), attribution, since, until)

    fun top(tags: List<TagStat>, limit: Int): List<TagStat> = tags.take(limit.coerceAtLeast(0))

    /** 快速复用看的是“最近写过”，不是“写得最多”；同一天再按出现次数与名称排序。 */
    fun recent(tags: List<TagStat>, limit: Int): List<TagStat> = tags
        .sortedWith(
            compareByDescending<TagStat> { it.lastUsed }
                .thenByDescending { it.sessionCount }
                .thenBy { it.tag },
        )
        .take(limit.coerceAtLeast(0))

    private class Bucket(val tag: String) {
        var sessions = 0
        var questions = 0
        var wrong = 0
        var seconds = 0L
        var last: LocalDate? = null
        val categories = linkedSetOf<Long>()
        val ids = mutableListOf<Long>()

        fun add(session: Session, date: LocalDate) {
            sessions++
            questions += session.totalCount
            wrong += session.totalCount - session.correctCount
            session.durationSec?.takeIf { it > 0 }?.let { seconds += it }
            categories += session.categoryId
            ids += session.id
            if (last == null || date.isAfter(last)) last = date
        }

        fun toStat() = TagStat(
            tag = tag,
            sessionCount = sessions,
            questionCount = questions,
            wrongCount = wrong,
            netSeconds = seconds,
            categoryIds = categories.toList(),
            sessionIds = ids.toList(),
            lastUsed = last ?: LocalDate.MIN,
        )
    }
}
