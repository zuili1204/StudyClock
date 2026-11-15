package com.tiji.studyclock

import com.tiji.studyclock.domain.model.RecordOrder
import com.tiji.studyclock.domain.model.RecordPaging
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionOrdering
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionOrderingTest {
    private fun session(id: Long, startAt: Long, total: Int, correct: Int) = Session(
        id = id, categoryId = 1, subjectId = 1, startAt = startAt,
        totalCount = total, correctCount = correct,
    )

    private val rows = listOf(
        session(1, 100, 20, 10), // 50%
        session(2, 200, 10, 5),  // 50%
        session(3, 300, 10, 9),  // 90%
        session(4, 400, 30, 6),  // 20%
    )

    @Test
    fun newestPutsLatestFirstAndBreaksTiesById() {
        val ids = SessionOrdering.sort(rows, RecordOrder.NEWEST).map { it.id }
        assertEquals(listOf(4L, 3L, 2L, 1L), ids)
    }

    @Test
    fun oldestIsTheMirrorOfNewest() {
        val ids = SessionOrdering.sort(rows, RecordOrder.OLDEST).map { it.id }
        assertEquals(listOf(1L, 2L, 3L, 4L), ids)
    }

    @Test
    fun accuracyAscendingBreaksTiesByMostRecent() {
        val ids = SessionOrdering.sort(rows, RecordOrder.ACCURACY_ASC).map { it.id }
        // 20% → 50%(较新) → 50%(较旧) → 90%
        assertEquals(listOf(4L, 2L, 1L, 3L), ids)
    }

    @Test
    fun accuracyDescendingBreaksTiesByMostRecent() {
        val ids = SessionOrdering.sort(rows, RecordOrder.ACCURACY_DESC).map { it.id }
        assertEquals(listOf(3L, 2L, 1L, 4L), ids)
    }

    @Test
    fun questionCountDescending() {
        val ids = SessionOrdering.sort(rows, RecordOrder.QUESTIONS_DESC).map { it.id }
        assertEquals(listOf(4L, 1L, 3L, 2L), ids)
    }

    @Test
    fun zeroQuestionSessionIsTreatedAsZeroAccuracy() {
        assertEquals(0.0, SessionOrdering.accuracy(session(9, 0, 0, 0)), 0.0001)
    }

    @Test
    fun onlyDateOrdersGroupByDay() {
        assertTrue(RecordOrder.NEWEST.groupsByDate)
        assertTrue(RecordOrder.OLDEST.groupsByDate)
        assertFalse(RecordOrder.ACCURACY_ASC.groupsByDate)
        assertFalse(RecordOrder.QUESTIONS_DESC.groupsByDate)
    }

    @Test
    fun pagingWindowGrowsByDaysAndStopsAtTotal() {
        val first = RecordPaging.window(totalDays = 25, shownDays = 0)
        assertEquals(10, first.shown)
        assertTrue(first.hasMore)
        assertEquals(15, first.remaining)

        val second = RecordPaging.window(totalDays = 25, shownDays = 10)
        assertEquals(20, second.shown)
        assertEquals(5, second.remaining)

        val last = RecordPaging.window(totalDays = 25, shownDays = 20)
        assertEquals(25, last.shown)
        assertFalse(last.hasMore)
        assertEquals(0, last.remaining)
    }

    @Test
    fun pagingWindowHandlesEmptyAndOverflow() {
        val empty = RecordPaging.window(totalDays = 0, shownDays = 0)
        assertEquals(0, empty.shown)
        assertFalse(empty.hasMore)
        val overflow = RecordPaging.window(totalDays = 3, shownDays = 99)
        assertEquals(3, overflow.shown)
        assertFalse(overflow.hasMore)
    }

    @Test
    fun pageSizeIsAtLeastOneDay() {
        val window = RecordPaging.window(totalDays = 5, shownDays = 0, pageSize = 0)
        assertEquals(1, window.shown)
    }
}
