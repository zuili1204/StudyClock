package com.tiji.studyclock

import com.tiji.studyclock.domain.model.Category
import com.tiji.studyclock.domain.model.DateAttribution
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.Subject
import com.tiji.studyclock.domain.stats.CoverageMap
import com.tiji.studyclock.domain.stats.CoverageStatus
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageTest {
    private val today = LocalDate.of(2026, 10, 30)
    private val subjects = listOf(
        Subject(1L, "数量关系", "#000000", 0),
        Subject(2L, "资料分析", "#000000", 1),
    )
    private val categories = listOf(
        Category(11L, 1L, "工程问题", sortOrder = 0),
        Category(12L, 1L, "行程问题", sortOrder = 1),
        Category(21L, 2L, "增长率", sortOrder = 0),
    )

    private fun session(
        id: Long,
        categoryId: Long,
        subjectId: Long,
        date: LocalDate = today,
        total: Int = 10,
        correct: Int = 8,
        endDate: LocalDate? = null,
    ): Session {
        val zone = ZoneId.systemDefault()
        val startAt = date.atTime(LocalTime.of(10, 0)).atZone(zone).toInstant().toEpochMilli()
        val endAt = endDate?.atTime(LocalTime.of(11, 0))?.atZone(zone)?.toInstant()?.toEpochMilli()
        return Session(
            id = id,
            categoryId = categoryId,
            subjectId = subjectId,
            startAt = startAt,
            endAt = endAt,
            totalCount = total,
            correctCount = correct,
        )
    }

    private fun map(sessions: List<Session>, attribution: DateAttribution = DateAttribution.START) =
        CoverageMap.calculate(sessions, categories, subjects, today, attribution)

    @Test
    fun emptyHistoryMarksEverythingUntouched() {
        val coverage = map(emptyList())
        assertEquals(CoverageStatus.UNTOUCHED, coverage.subjects[0].status)
        assertEquals(CoverageStatus.UNTOUCHED, coverage.subjects[1].status)
        assertEquals(3, coverage.totalCategories)
        assertEquals(0, coverage.practicedCategories)
        assertEquals(3, coverage.untouchedCategories)
        assertEquals(0, coverage.staleCategories)
        assertFalse(coverage.hasAnyPractice)
        assertEquals("还有 3 个类别没有记录。", coverage.gapSummary())
    }

    @Test
    fun trustedNeedsBothQuestionsAndSessions() {
        val trusted = map((1..3).map { session(it.toLong(), 11L, 1L) })
        assertEquals(CoverageStatus.TRUSTED, trusted.subjects[0].categories.single().status)
        assertEquals(CoverageStatus.TRUSTED, trusted.subjects[0].status)
        assertEquals(1, trusted.practicedCategories)
        assertEquals(2, trusted.untouchedCategories)

        val enoughQuestions = map(listOf(session(1, 11L, 1L, total = 30)))
        assertEquals(CoverageStatus.SAMPLING, enoughQuestions.subjects[0].categories.single().status)

        val enoughSessions = map((1..3).map { session(it.toLong(), 11L, 1L, total = 4, correct = 3) })
        assertEquals(CoverageStatus.SAMPLING, enoughSessions.subjects[0].categories.single().status)
    }

    @Test
    fun subjectAggregatesAcrossCategories() {
        val sessions = listOf(
            session(1, 11L, 1L, total = 15),
            session(2, 12L, 1L, total = 10),
            session(3, 12L, 1L, total = 5, correct = 4),
        )
        val subject = map(sessions).subjects[0]
        assertEquals(CoverageStatus.TRUSTED, subject.status)
        assertEquals(30, subject.questionCount)
        assertEquals(3, subject.sessionCount)
        assertEquals(2, subject.categories.size)
        assertTrue(subject.categories.all { it.status == CoverageStatus.SAMPLING })
    }

    @Test
    fun staleStartsAfterFourteenDays() {
        val exactlyFourteen = map(listOf(session(1, 11L, 1L, date = today.minusDays(14))))
        assertFalse(exactlyFourteen.subjects[0].categories.single().isStale)
        assertEquals(0, exactlyFourteen.staleCategories)

        val fifteen = map(listOf(session(1, 11L, 1L, date = today.minusDays(15))))
        assertTrue(fifteen.subjects[0].categories.single().isStale)
        assertTrue(fifteen.subjects[0].isStale)
        assertEquals(1, fifteen.staleCategories)
        assertEquals("还有 2 个类别没有记录；1 个类别超过 14 天未练。", fifteen.gapSummary())
    }

    @Test
    fun drillDownListsOnlyPracticedCategories() {
        val coverage = map(listOf(session(1, 11L, 1L)))
        val subject = coverage.subjects[0]
        assertEquals(listOf(11L), subject.categories.map { it.categoryId })
        assertEquals(1, subject.untouchedCategories)
        assertEquals(0, coverage.subjects[1].categories.size)
        assertEquals(1, coverage.subjects[1].untouchedCategories)
    }

    @Test
    fun archivedCategoriesDoNotCreateGaps() {
        val archivedCatalog = listOf(
            Category(11L, 1L, "工程问题", sortOrder = 0, archived = true),
            Category(21L, 2L, "增长率", sortOrder = 0),
        )
        val coverage = CoverageMap.calculate(emptyList(), archivedCatalog, subjects, today)
        assertEquals(1, coverage.totalCategories)
        assertEquals(1, coverage.untouchedCategories)

        val withHistory = listOf(archivedCatalog[0], archivedCatalog[1])
        val practiced = CoverageMap.calculate(listOf(session(1, 11L, 1L)), withHistory, subjects, today)
        assertEquals(2, practiced.totalCategories)
        assertEquals(1, practiced.practicedCategories)
        assertEquals(1, practiced.untouchedCategories)
        assertEquals(listOf(11L), practiced.subjects[0].categories.map { it.categoryId })
    }

    @Test
    fun invalidAndFutureSessionsAreIgnored() {
        val invalid = listOf(
            session(1, 11L, 1L, total = 0),
            session(2, 11L, 1L, total = 5, correct = 6),
            session(3, 11L, 1L, date = today.plusDays(1)),
        )
        val coverage = map(invalid)
        assertFalse(coverage.hasAnyPractice)
        assertEquals(3, coverage.untouchedCategories)
    }

    @Test
    fun endAttributionUsesEndDate() {
        val crossMidnight = listOf(
            session(1, 11L, 1L, date = today.minusDays(20), endDate = today.minusDays(1)),
        )
        val start = map(crossMidnight, DateAttribution.START)
        assertEquals(today.minusDays(20), start.subjects[0].lastPracticedDate)
        assertTrue(start.staleCategories == 1)

        val end = map(crossMidnight, DateAttribution.END)
        assertEquals(today.minusDays(1), end.subjects[0].lastPracticedDate)
        assertFalse(end.subjects[0].categories.single().isStale)
    }
}
