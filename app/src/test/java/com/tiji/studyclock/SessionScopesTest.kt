package com.tiji.studyclock

import com.tiji.studyclock.domain.model.RecordMode
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionKind
import com.tiji.studyclock.domain.model.SessionScopes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 质量闸 F：样本切分只有一个出口，SCORE 与 REDO 绝不进入主正确率。 */
class SessionScopesTest {
    private fun session(
        id: Long,
        mode: RecordMode = RecordMode.COUNT,
        kind: SessionKind = SessionKind.NORMAL,
        total: Int = 10,
        correct: Int = 7,
        score: Int? = null,
        max: Int? = null,
        tags: String? = null,
        batchId: Long? = null,
    ) = Session(
        id = id, categoryId = 1, subjectId = 1, startAt = 1_000L + id,
        totalCount = total, correctCount = correct,
        mode = mode, kind = kind, scoreTenths = score, maxScoreTenths = max,
        wrongTags = tags, batchId = batchId,
    )

    private val four = listOf(
        session(1),
        session(2, mode = RecordMode.SCORE, correct = 0, score = 75, max = 90),
        session(3, kind = SessionKind.REDO, batchId = 9, total = 3, correct = 2),
        session(4, mode = RecordMode.SCORE, kind = SessionKind.REDO, batchId = 9,
            correct = 1, score = 60, max = 100),
    )

    @Test
    fun activityKeepsEveryValidSession() {
        assertEquals(listOf(1L, 2L, 3L, 4L), SessionScopes.activity(four).map { it.id })
        assertEquals(4, SessionScopes.activity(shuffled()).size)
    }

    @Test
    fun performanceOnlyCountNormal() {
        assertEquals(listOf(1L), SessionScopes.performance(four).map { it.id })
        assertTrue(SessionScopes.performance(four).none { it.mode == RecordMode.SCORE })
        assertTrue(SessionScopes.performance(four).none { it.kind == SessionKind.REDO })
    }

    @Test
    fun scoredAndRedoAreIndependentTracks() {
        assertEquals(listOf(2L, 4L), SessionScopes.scored(four).map { it.id })
        assertEquals(listOf(3L, 4L), SessionScopes.redo(four).map { it.id })
        // 交集是「计分题复做」，两条轨都该看到它，但主正确率永远看不到。
        assertEquals(listOf(4L), SessionScopes.scored(four).filter { it.kind == SessionKind.REDO }.map { it.id })
    }

    @Test
    fun invalidScoreRowsAreExcludedFromScoreTrack() {
        val bad = listOf(
            session(5, mode = RecordMode.SCORE, correct = 0, score = null, max = 100),
            session(6, mode = RecordMode.SCORE, correct = 0, score = 120, max = 100),
            session(7, mode = RecordMode.SCORE, correct = 0, score = 80, max = 0),
        )
        assertTrue(SessionScopes.scored(bad).isEmpty())
    }

    @Test
    fun tagSamplesAreSeparated() {
        val rows = listOf(
            session(1, correct = 6, tags = "增长率"),
            session(2, correct = 10, tags = "全对"),
            session(3, mode = RecordMode.SCORE, correct = 0, score = 70, max = 100, tags = "论据不足"),
            session(4, kind = SessionKind.REDO, batchId = 9, total = 3, correct = 2, tags = "审题"),
        )
        assertEquals(listOf(1L), SessionScopes.objectiveWrong(rows).map { it.id })
        assertEquals(listOf(3L, 4L), SessionScopes.otherTagged(rows).map { it.id })
    }

    @Test
    fun prescriptionSampleExcludesRedo() {
        assertEquals(listOf(1L), SessionScopes.prescriptionSample(four).map { it.id })
    }

    @Test
    fun emptyAndShuffledInputs() {
        assertTrue(SessionScopes.activity(emptyList()).isEmpty())
        assertTrue(SessionScopes.performance(emptyList()).isEmpty())
        assertTrue(SessionScopes.scored(emptyList()).isEmpty())
        assertTrue(SessionScopes.redo(emptyList()).isEmpty())
        assertEquals(listOf(1L), SessionScopes.performance(shuffled()).map { it.id })
    }

    /** D28：专注进投入面，但不进任何表现轨，也不虚增题量。 */
    @Test
    fun focusJoinsActivityButNeverPerformance() {
        val rows = four + session(5, mode = RecordMode.FOCUS, total = 0, correct = 0)
            .copy(durationSec = 1800)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), SessionScopes.activity(rows).map { it.id })
        assertEquals(listOf(1L), SessionScopes.performance(rows).map { it.id })
        assertEquals(listOf(2L, 4L), SessionScopes.scored(rows).map { it.id })
        assertEquals(listOf(3L, 4L), SessionScopes.redo(rows).map { it.id })
        assertEquals(listOf(5L), SessionScopes.focus(rows).map { it.id })
        assertTrue(SessionScopes.isObjective(rows.last()).not())
        // 题量只由 totalCount 求和，专注贡献 0 题（10 + 10 + 3 + 10 + 0）。
        assertEquals(33, SessionScopes.activity(rows).sumOf { it.totalCount })
    }

    /** 没有时长的 FOCUS 记录是脏数据，任何口径都不该看到它。 */
    @Test
    fun focusWithoutDurationDropsOutOfEveryScope() {
        val empty = listOf(
            session(6, mode = RecordMode.FOCUS, total = 0, correct = 0),
            session(7, mode = RecordMode.FOCUS, total = 0, correct = 0).copy(durationSec = 0),
        )
        assertTrue(SessionScopes.activity(empty).isEmpty())
        assertTrue(SessionScopes.focus(empty).isEmpty())
        assertTrue(SessionScopes.performance(empty).isEmpty())
    }

    private fun shuffled(): List<Session> = listOf(four[3], four[1], four[0], four[2])
}
