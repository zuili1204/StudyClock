package com.tiji.studyclock.domain.model

/** 记录列表的排序方式。数值型排序一律以“最近一次在前”作为并列时的次序，避免顺序抖动。 */
enum class RecordOrder(val label: String) {
    NEWEST("最近优先"),
    OLDEST("最早优先"),
    ACCURACY_ASC("正确率从低到高"),
    ACCURACY_DESC("正确率从高到低"),
    QUESTIONS_DESC("题量从多到少"),
    ;

    val groupsByDate: Boolean get() = this == NEWEST || this == OLDEST
}

object SessionOrdering {
    fun accuracy(session: Session): Double =
        if (session.totalCount <= 0) 0.0 else session.correctCount.toDouble() / session.totalCount

    fun comparator(order: RecordOrder): Comparator<Session> = when (order) {
        RecordOrder.NEWEST -> compareByDescending<Session> { it.startAt }.thenByDescending { it.id }
        RecordOrder.OLDEST -> compareBy<Session> { it.startAt }.thenBy { it.id }
        RecordOrder.ACCURACY_ASC -> compareBy<Session> { accuracy(it) }.thenByDescending { it.startAt }
        RecordOrder.ACCURACY_DESC -> compareByDescending<Session> { accuracy(it) }.thenByDescending { it.startAt }
        RecordOrder.QUESTIONS_DESC -> compareByDescending<Session> { it.totalCount }.thenByDescending { it.startAt }
    }

    fun sort(sessions: List<Session>, order: RecordOrder): List<Session> = sessions.sortedWith(comparator(order))
}

/**
 * 长列表分页窗口：按“天”翻页而不是按条，避免日期分组被切一半。
 * 返回值是应当显示的组数与是否还有更多。
 */
object RecordPaging {
    const val PAGE_DAYS = 10

    fun window(totalDays: Int, shownDays: Int, pageSize: Int = PAGE_DAYS): PageWindow {
        val safeTotal = totalDays.coerceAtLeast(0)
        val shown = shownDays.coerceIn(0, safeTotal)
        val next = (shown + pageSize.coerceAtLeast(1)).coerceAtMost(safeTotal)
        return PageWindow(shown = next, hasMore = next < safeTotal, remaining = safeTotal - next)
    }

    data class PageWindow(val shown: Int, val hasMore: Boolean, val remaining: Int)
}
