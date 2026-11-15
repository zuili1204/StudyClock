package com.tiji.studyclock

import com.tiji.studyclock.domain.model.Category
import com.tiji.studyclock.domain.model.DateAttribution
import com.tiji.studyclock.domain.model.RecordMode
import com.tiji.studyclock.domain.model.ReviewAttempt
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionKind
import com.tiji.studyclock.domain.model.SessionScopes
import com.tiji.studyclock.domain.model.WrongBatch
import com.tiji.studyclock.domain.stats.CrossModeStats
import com.tiji.studyclock.domain.stats.DayStats
import com.tiji.studyclock.domain.stats.ModeBucket
import com.tiji.studyclock.domain.stats.RedoLedger
import com.tiji.studyclock.domain.stats.ScoreEngine
import com.tiji.studyclock.domain.stats.StatsEngine
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §4.5 的口径不变量：三轨互不泄漏、活动面与表现面账目对得上。 */
class CrossModeStatsTest {
    private val today = LocalDate.of(2026, 11, 15)
    private val zone = ZoneId.systemDefault()
    private val categories = listOf(Category(1, 1, "主旨概括"), Category(2, 1, "文章写作"))

    private fun at(day: Int): Long = today.minusDays(day.toLong()).atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000L

    private fun session(
        id: Long,
        total: Int,
        correct: Int,
        mode: RecordMode = RecordMode.COUNT,
        kind: SessionKind = SessionKind.NORMAL,
        score: Int? = null,
        max: Int? = null,
        categoryId: Long = 1,
        day: Int = 1,
        tags: String? = null,
    ) = Session(
        id = id, categoryId = categoryId, subjectId = 1, startAt = at(day),
        totalCount = total, correctCount = correct, durationSec = 600,
        mode = mode, kind = kind, scoreTenths = score, maxScoreTenths = max,
        batchId = if (kind == SessionKind.REDO) 9L else null,
        wrongTags = tags,
    )

    private val mixed = listOf(
        session(1, 10, 8, day = 5, tags = "增长率"),                                // COUNT + NORMAL
        session(2, 2, 0, mode = RecordMode.SCORE, score = 75, max = 90, day = 4),   // SCORE
        session(3, 3, 2, kind = SessionKind.REDO, day = 3, tags = "审题"),           // REDO
        session(4, 1, 0, mode = RecordMode.SCORE, kind = SessionKind.REDO,
            score = 60, max = 100, categoryId = 2, day = 2),                        // SCORE + REDO
    )

    @Test
    fun invariant1_scoreAndRedoNeverEnterAccuracy() {
        val snapshot = StatsEngine.calculate(mixed, categories, today, zone)
        assertEquals(0.8, snapshot.accuracy.value!!, 1e-12)
        assertEquals(10, snapshot.accuracy.questionCount)
        assertEquals(1, snapshot.accuracy.sessionCount)
    }

    @Test
    fun invariant2_scoreZeroCorrectNeverShowsZeroPercent() {
        val onlyScores = listOf(mixed[1], mixed[3])
        val snapshot = StatsEngine.calculate(onlyScores, categories, today, zone)
        assertNull(snapshot.accuracy.value)
        assertNull("没有客观题样本时应为 NULL 而非 0%", snapshot.accuracy.value)
        val score = ScoreEngine.calculate(onlyScores, today, zone)
        assertEquals((75 + 60).toDouble() / (90 + 100), score.rate.value!!, 1e-12)
    }

    @Test
    fun invariant3_activityQuestionsSplitExactly() {
        val snapshot = StatsEngine.calculate(mixed, categories, today, zone)
        val activity = SessionScopes.activity(mixed).sumOf { it.totalCount }
        val objective = SessionScopes.performance(mixed).sumOf { it.totalCount }
        val scoredOnly = SessionScopes.scored(mixed).filter { !SessionScopes.isRedo(it) }.sumOf { it.totalCount }
        val redo = SessionScopes.redo(mixed).sumOf { it.totalCount }
        assertEquals(activity, snapshot.activityQuestions)
        assertEquals(activity, objective + scoredOnly + redo)
        assertTrue(activity >= objective)
        assertEquals(16, activity)
        assertEquals(10, objective)
    }

    @Test
    fun x1StructurePartitionsAllSessions() {
        val structure = CrossModeStats.structure(mixed)
        assertEquals(4, structure.totalSessions)
        assertEquals(16, structure.totalQuestions)
        assertEquals(2400L, structure.totalSeconds)
        // v0.11：四象限固定输出，即使没有专注也要占一格，避免条形图跳动。
        assertEquals(4, structure.shares.size)
        assertEquals(1, structure.shares.single { it.bucket == ModeBucket.OBJECTIVE }.sessions)
        assertEquals(1, structure.shares.single { it.bucket == ModeBucket.SCORED }.sessions)
        assertEquals(2, structure.shares.single { it.bucket == ModeBucket.REDO }.sessions)
        val focus = structure.shares.single { it.bucket == ModeBucket.FOCUS }
        assertEquals(0, focus.sessions)
        assertEquals(0, focus.questions)
        assertTrue(kotlin.math.abs(structure.shares.sumOf { it.questionShare } - 1.0) < 1e-12)
        assertEquals(16, structure.shares.sumOf { it.questions })
    }

    /** 专注只贡献时长，不贡献题量：四象限题量合计仍是 16，时长合计才增加。 */
    @Test
    fun x1FocusAddsDurationOnly() {
        val focus = session(5, 0, 0, mode = RecordMode.FOCUS, day = 1).copy(durationSec = 1800)
        val structure = CrossModeStats.structure(mixed + focus)
        assertEquals(5, structure.totalSessions)
        assertEquals(16, structure.totalQuestions)
        assertEquals(4200L, structure.totalSeconds)
        val bucket = structure.shares.single { it.bucket == ModeBucket.FOCUS }
        assertEquals(1, bucket.sessions)
        assertEquals(0, bucket.questions)
        assertEquals(1800L, bucket.timedSeconds)
        assertEquals(0.0, bucket.questionShare, 1e-12)
        assertTrue(kotlin.math.abs(structure.shares.sumOf { it.durationShare } - 1.0) < 1e-12)
    }

    @Test
    fun invariant6_dayQuestionTotalsMatchRecordList() {
        val day = today.minusDays(5)
        val rows = listOf(mixed[0], mixed[1].copy(startAt = at(5)), mixed[2].copy(startAt = at(5)))
        val summary = DayStats.summarize(rows, day, DateAttribution.START)
        assertEquals(15, summary.questions)
        assertEquals(10, summary.performanceQuestions)
        assertEquals(8, summary.correct)
        assertEquals(0.8, summary.accuracy!!, 1e-12)
        assertEquals(rows.sumOf { it.totalCount }, summary.questions)
    }

    @Test
    fun x2AndX3OnlyDescribeFactsWithoutMerging() {
        val batches = listOf(
            WrongBatch(9, 1, 1, 1, wrongCount = 5, wrongTags = "增长率", originDate = "2026-11-01"),
        )
        val attempts = listOf(
            ReviewAttempt(1, 9, 3, 3, 2, at(3)),
            ReviewAttempt(2, 9, 4, 2, 1, at(2)),
        )
        val count = StatsEngine.calculate(mixed, categories, today, zone)
        val score = ScoreEngine.calculate(mixed, today, zone)
        val redo = RedoLedger.metrics(batches, attempts, today, zone)
        val comparisons = CrossModeStats.compareByCategory(mixed, categories, count, score, batches, attempts, today)
        // 三轨样本都不足时整行不出现：不补 0%、不平均、不生成综合分。
        assertTrue("样本不足时不应出现分轨对照行", comparisons.isEmpty())
        assertNotNull(count)
        assertNotNull(score)
        assertNotNull(redo)
        val cross = CrossModeStats.tagCross(mixed, batches, attempts, today)
        assertTrue(cross.isNotEmpty())
        assertEquals(2, RedoLedger.ledger(batches.first(), attempts, today, zone).remaining)
    }
}
