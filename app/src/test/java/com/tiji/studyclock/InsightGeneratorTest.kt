package com.tiji.studyclock

import com.tiji.studyclock.domain.diagnose.Insight
import com.tiji.studyclock.domain.diagnose.InsightGenerator
import com.tiji.studyclock.domain.model.Category
import com.tiji.studyclock.domain.model.DateAttribution
import com.tiji.studyclock.domain.model.ExamTarget
import com.tiji.studyclock.domain.model.ExamType
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.Subject
import com.tiji.studyclock.domain.stats.Consistency
import com.tiji.studyclock.domain.stats.ConfidenceLevel
import com.tiji.studyclock.domain.stats.HourStats
import com.tiji.studyclock.domain.stats.Metric
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightGeneratorTest {
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 10, 28)
    private val categories = listOf(Category(1, 1, "语句填充"), Category(2, 1, "片段阅读"))

    private fun session(
        date: LocalDate,
        categoryId: Long = 1,
        total: Int = 16,
        correct: Int = 12,
        hour: Int = 21,
        subjectId: Long = 1,
    ): Session {
        val start = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        return Session(
            id = 0, categoryId = categoryId, subjectId = subjectId, startAt = start, endAt = start + 600_000,
            durationSec = 600, totalCount = total, correctCount = correct,
        )
    }

    private fun hourStats(hour: Int, accuracy: Double, questions: Int = 30) = HourStats(
        hour = hour,
        accuracy = Metric(accuracy, ConfidenceLevel.TRUSTED, questions, 2, "%"),
        sessionIds = listOf(1),
    )

    private fun run(
        sessions: List<Session>,
        hours: List<HourStats> = emptyList(),
        goal: Int? = null,
        max: Int = InsightGenerator.MAX_INSIGHTS,
        subjects: List<Subject> = listOf(Subject(1, "言语理解与判断", "#000000", 0)),
        exam: ExamTarget = ExamTarget(type = ExamType.CIVIL_SERVANT, name = "考公", examDate = today.plusDays(30)),
    ): List<Insight> {
        val streak = Consistency.summarize(sessions, today, DateAttribution.START)
        return InsightGenerator.generate(
            sessions = sessions, categories = categories, subjects = subjects, exam = exam,
            hourStats = hours, streak = streak, today = today, attribution = DateAttribution.START,
            dailyQuestionGoal = goal, max = max,
        )
    }

    @Test
    fun twoMissingDaysSurfaceAGap() {
        val insights = run(listOf(session(today.minusDays(3))))
        assertEquals("gap", insights.first().id)
        assertEquals(Insight.Tone.ATTENTION, insights.first().tone)
        assertTrue(insights.first().title.contains("3 天"))
    }

    @Test
    fun threeDayStreakIsCelebrated() {
        val insights = run(listOf(session(today), session(today.minusDays(1)), session(today.minusDays(2))))
        val streak = insights.first { it.id == "streak" }
        assertEquals(Insight.Tone.POSITIVE, streak.tone)
        assertTrue(streak.title.contains("连续 3 天"))
    }

    @Test
    fun hourGapUsesTwentyQuestionFloor() {
        val insights = run(emptyList(), listOf(hourStats(15, 0.60), hourStats(21, 0.75)))
        val hour = insights.first { it.id == "hour" }
        assertTrue(hour.title, hour.title.contains("21 点比 15 点"))
        assertTrue(hour.title.contains("15 pt"))
    }

    @Test
    fun hourGapIgnoresThinOrCloseHours() {
        assertTrue(run(emptyList(), listOf(hourStats(15, 0.60, questions = 12), hourStats(21, 0.80))).none { it.id == "hour" })
        assertTrue(run(emptyList(), listOf(hourStats(15, 0.70), hourStats(21, 0.74))).none { it.id == "hour" })
    }

    @Test
    fun stagnationComparesLastThreeWithEarlier() {
        val sessions = listOf(
            session(today.minusDays(7), total = 16, correct = 13),
            session(today.minusDays(6), total = 16, correct = 13),
            session(today.minusDays(3), total = 16, correct = 8),
            session(today.minusDays(2), total = 16, correct = 8),
            session(today.minusDays(1), total = 16, correct = 8),
        )
        val insight = run(sessions).firstOrNull { it.id == "stagnation" }
        assertTrue("应识别出回落", insight != null)
        assertTrue(insight!!.title.contains("语句填充"))
        assertTrue("应写明基线样本", insight.detail.contains("此前 2 次 32 题"))
        assertEquals(Insight.Tone.ATTENTION, insight.tone)
    }

    @Test
    fun stagnationNeedsTwoBaselineSessions() {
        // 4 次时“此前”只有 1 次，单次波动不足以称为趋势。
        val sessions = listOf(
            session(today.minusDays(7), total = 16, correct = 13),
            session(today.minusDays(3), total = 16, correct = 8),
            session(today.minusDays(2), total = 16, correct = 8),
            session(today.minusDays(1), total = 16, correct = 8),
        )
        assertTrue(run(sessions).none { it.id == "stagnation" })
    }

    @Test
    fun coverageFlagsUntouchedSubjects() {
        val subjects = listOf(
            Subject(1, "言语理解与判断", "#000000", 0),
            Subject(2, "数量关系", "#000000", 1),
            Subject(3, "资料分析", "#000000", 2),
        )
        val insight = run(listOf(session(today)), subjects = subjects).firstOrNull { it.id == "coverage" }
        assertTrue("应指出未覆盖科目", insight != null)
        assertEquals(Insight.Tone.ATTENTION, insight!!.tone)
        assertTrue(insight.title, insight.title.contains("2 个科目还是 0 题"))
        assertTrue(insight.detail, insight.detail.contains("数量关系"))
        assertTrue(insight.detail.contains("资料分析"))
    }

    @Test
    fun coverageSilentWithoutExamDate() {
        val subjects = listOf(Subject(1, "言语理解与判断", "#000000", 0), Subject(2, "数量关系", "#000000", 1))
        val noDate = ExamTarget(type = ExamType.CIVIL_SERVANT, name = "考公", examDate = null)
        assertTrue(run(listOf(session(today)), subjects = subjects, exam = noDate).none { it.id == "coverage" })
    }

    @Test
    fun coverageSilentWhenEverySubjectPracticed() {
        val subjects = listOf(Subject(1, "言语理解与判断", "#000000", 0), Subject(2, "数量关系", "#000000", 1))
        val sessions = listOf(session(today), session(today, categoryId = 2, subjectId = 2))
        assertTrue(run(sessions, subjects = subjects).none { it.id == "coverage" })
    }

    @Test
    fun stagnationNeedsEnoughSessions() {
        val sessions = listOf(
            session(today.minusDays(4), total = 16, correct = 14),
            session(today.minusDays(3), total = 16, correct = 14),
            session(today.minusDays(2), total = 16, correct = 6),
        )
        assertTrue(run(sessions).none { it.id == "stagnation" })
    }

    @Test
    fun goalMissedMostDaysIsFlagged() {
        val sessions = listOf(session(today, total = 20), session(today.minusDays(3), total = 5))
        val insight = run(sessions, goal = 20).firstOrNull { it.id == "goal" }
        assertTrue(insight != null)
        assertEquals(Insight.Tone.ATTENTION, insight!!.tone)
    }

    @Test
    fun goalHitEveryDayIsPositive() {
        val sessions = (0L..6L).map { session(today.minusDays(it), total = 20) }
        val insight = run(sessions, goal = 20).firstOrNull { it.id == "goal" }
        assertTrue(insight != null)
        assertEquals(Insight.Tone.POSITIVE, insight!!.tone)
    }

    @Test
    fun noGoalNoGoalInsight() {
        assertTrue(run(listOf(session(today))).none { it.id == "goal" })
    }

    @Test
    fun repeatedWrongTagBecomesConcentrationInsight() {
        val sessions = (0L..3L).map { session(today.minusDays(it)).copy(wrongTags = "属性规律") }
        val insight = run(sessions).firstOrNull { it.id == "tag" }
        assertTrue("应识别出集中的关键词", insight != null)
        assertEquals(Insight.Tone.ATTENTION, insight!!.tone)
        assertTrue(insight.title.contains("属性规律"))
        assertTrue(insight.detail.contains("4 次"))
    }

    @Test
    fun tagNeedsThreeMentionsAndAtLeastHalfShare() {
        val two = (0L..1L).map { session(today.minusDays(it)).copy(wrongTags = "属性规律") }
        assertTrue(run(two).none { it.id == "tag" })

        val split = listOf(
            session(today.minusDays(3)).copy(wrongTags = "属性规律"),
            session(today.minusDays(2)).copy(wrongTags = "属性规律"),
            session(today.minusDays(1)).copy(wrongTags = "增长率"),
            session(today).copy(wrongTags = "增长率"),
        )
        assertTrue(run(split).none { it.id == "tag" })
    }

    @Test
    fun tagsOnCorrectOrOldSessionsDoNotFormAHabit() {
        val correct = (0L..3L).map { session(today.minusDays(it), correct = 16).copy(wrongTags = "粗心") }
        assertTrue(run(correct).none { it.id == "tag" })

        val old = (0L..3L).map { session(today.minusDays(20 + it)).copy(wrongTags = "属性规律") }
        assertTrue(run(old).none { it.id == "tag" })
    }

    @Test
    fun emptyHistoryProducesNoInsight() {
        assertTrue(run(emptyList()).isEmpty())
    }

    @Test
    fun attentionComesBeforePositiveAndCountIsCapped() {
        val sessions = buildList {
            add(session(today.minusDays(5), total = 16, correct = 14))
            add(session(today.minusDays(4), total = 16, correct = 14))
            add(session(today.minusDays(3), total = 16, correct = 6))
            add(session(today.minusDays(2), total = 16, correct = 6))
            add(session(today.minusDays(1), total = 4))
        }
        val insights = run(sessions, listOf(hourStats(15, 0.5), hourStats(21, 0.8)), goal = 20, max = 3)
        assertEquals(3, insights.size)
        assertEquals(Insight.Tone.ATTENTION, insights.first().tone)
        assertFalse(insights.any { it.tone == Insight.Tone.POSITIVE })
    }
}
