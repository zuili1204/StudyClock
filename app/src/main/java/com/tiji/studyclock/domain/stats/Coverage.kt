package com.tiji.studyclock.domain.stats

import com.tiji.studyclock.domain.model.Category
import com.tiji.studyclock.domain.model.DateAttribution
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionScopes
import com.tiji.studyclock.domain.model.Subject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 覆盖只回答“有没有开始”，不回答“掌握到什么程度”。 */
enum class CoverageStatus { UNTOUCHED, SAMPLING, TRUSTED }

data class CategoryCoverage(
    val categoryId: Long,
    val subjectId: Long,
    val status: CoverageStatus,
    val questionCount: Int,
    val sessionCount: Int,
    val lastPracticedDate: LocalDate?,
    val daysSinceLastPractice: Long?,
) {
    val isStale: Boolean get() = CoverageMap.isStale(daysSinceLastPractice)
}

data class SubjectCoverage(
    val subjectId: Long,
    val status: CoverageStatus,
    val questionCount: Int,
    val sessionCount: Int,
    val lastPracticedDate: LocalDate?,
    val daysSinceLastPractice: Long?,
    /** 该科目下还没记录过的类别数；只统计未归档类别。 */
    val untouchedCategories: Int,
    /** 只列练过的类别：真实历史里大量类别只出现过一次，全列会变成清单压力。 */
    val categories: List<CategoryCoverage>,
) {
    val isStale: Boolean get() = CoverageMap.isStale(daysSinceLastPractice)
}

/**
 * 覆盖地图：按科目汇总、按类别下钻。
 * 与统计引擎共用“有效记录”口径（题量大于 0、答对数不超过题量、日期不晚于今天），
 * 避免同一份数据在看板和覆盖屏出现两套分母。
 */
data class CoverageMap(
    val subjects: List<SubjectCoverage>,
    val totalCategories: Int,
    val practicedCategories: Int,
    val untouchedCategories: Int,
    val staleCategories: Int,
) {
    val hasAnyPractice: Boolean get() = practicedCategories > 0 || subjects.any { it.sessionCount > 0 }

    /** 缺口文案与 M1-3 约定一致：“还有 N 个类别没有记录；M 个类别超过 14 天未练。” */
    fun gapSummary(): String {
        val gap = if (untouchedCategories == 0) "所有类别都至少练过一次" else "还有 $untouchedCategories 个类别没有记录"
        val stale = if (staleCategories == 0) null else "$staleCategories 个类别超过 $STALE_DAYS 天未练"
        return if (stale == null) "$gap。" else "$gap；$stale。"
    }

    companion object {
        /** 超过两周没碰，才算“久未练”；刚停几天不制造焦虑。 */
        const val STALE_DAYS = 14L

        fun isStale(daysSinceLastPractice: Long?): Boolean =
            daysSinceLastPractice != null && daysSinceLastPractice > STALE_DAYS

        fun calculate(
            sessions: List<Session>,
            categories: List<Category>,
            subjects: List<Subject>,
            today: LocalDate,
            attribution: DateAttribution = DateAttribution.START,
        ): CoverageMap {
            // 投入面：有题量的练习或有效专注；专注只证明“碰过”，不参与题量与正确率。
            val history = sessions.asSequence()
                .filter(SessionScopes::isActivity)
                .filter { !sessionDate(it, attribution).isAfter(today) }
                .toList()
            val byCategory = history.groupBy { it.categoryId }
            // 归档类别不再算缺口；但已有历史的仍保留，避免历史记录凭空消失。
            val catalog = categories
                .filter { !it.archived || byCategory.containsKey(it.id) }
                .sortedWith(compareBy<Category> { it.sortOrder }.thenBy { it.id })
            val catalogById = catalog.associateBy { it.id }

            val categoryRows = (catalogById.keys + byCategory.keys).associate { categoryId ->
                val rows = byCategory[categoryId].orEmpty()
                val last = rows.maxOfOrNull { sessionDate(it, attribution) }
                categoryId to CategoryCoverage(
                    categoryId = categoryId,
                    subjectId = catalogById[categoryId]?.subjectId ?: rows.last().subjectId,
                    status = statusOf(rows.size, rows.sumOf { it.totalCount }),
                    questionCount = rows.sumOf { it.totalCount },
                    sessionCount = rows.size,
                    lastPracticedDate = last,
                    daysSinceLastPractice = last?.let { ChronoUnit.DAYS.between(it, today) },
                )
            }

            val subjectRows = subjects
                .filter { subject ->
                    catalogById.values.any { it.subjectId == subject.id } ||
                        history.any { it.subjectId == subject.id }
                }
                .sortedWith(compareBy<Subject> { it.sortOrder }.thenBy { it.id })
                .map { subject ->
                    val practiced = categoryRows.values
                        .filter { it.subjectId == subject.id && it.sessionCount > 0 }
                        .sortedWith(
                            compareByDescending<CategoryCoverage> { it.status.ordinal }
                                .thenByDescending { it.daysSinceLastPractice ?: -1L }
                                .thenBy { it.categoryId },
                        )
                    val sessionCount = practiced.sumOf { it.sessionCount }
                    val questionCount = practiced.sumOf { it.questionCount }
                    val last = practiced.mapNotNull { it.lastPracticedDate }.maxOrNull()
                    SubjectCoverage(
                        subjectId = subject.id,
                        status = statusOf(sessionCount, questionCount),
                        questionCount = questionCount,
                        sessionCount = sessionCount,
                        lastPracticedDate = last,
                        daysSinceLastPractice = last?.let { ChronoUnit.DAYS.between(it, today) },
                        untouchedCategories = catalog.count { it.subjectId == subject.id && it.id !in byCategory },
                        categories = practiced,
                    )
                }

            val activeIds = catalog.filter { !it.archived }.mapTo(mutableSetOf()) { it.id }
            return CoverageMap(
                subjects = subjectRows,
                totalCategories = catalog.size,
                practicedCategories = catalog.count { it.id in byCategory },
                untouchedCategories = activeIds.count { it !in byCategory },
                staleCategories = activeIds.count { id ->
                    byCategory[id]?.maxOfOrNull { sessionDate(it, attribution) }
                        ?.let { ChronoUnit.DAYS.between(it, today) > STALE_DAYS } == true
                },
            )
        }

        private fun statusOf(sessionCount: Int, questionCount: Int): CoverageStatus = when {
            sessionCount == 0 -> CoverageStatus.UNTOUCHED
            Thresholds.confidence(questionCount, sessionCount) == ConfidenceLevel.TRUSTED -> CoverageStatus.TRUSTED
            else -> CoverageStatus.SAMPLING
        }
    }
}
