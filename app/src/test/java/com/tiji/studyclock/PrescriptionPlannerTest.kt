package com.tiji.studyclock

import com.tiji.studyclock.domain.model.Category
import com.tiji.studyclock.domain.model.DateAttribution
import com.tiji.studyclock.domain.model.ExamTarget
import com.tiji.studyclock.domain.model.ExamType
import com.tiji.studyclock.domain.model.PrescriptionType
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.UserSettings
import com.tiji.studyclock.domain.prescribe.PrescriptionPlanner
import com.tiji.studyclock.domain.stats.CategoryStats
import com.tiji.studyclock.domain.stats.ConfidenceLevel
import com.tiji.studyclock.domain.stats.Metric
import com.tiji.studyclock.domain.stats.SpeedBaseline
import com.tiji.studyclock.domain.stats.Thresholds
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrescriptionPlannerTest {
    private val today = LocalDate.of(2026, 10, 30)
    private val zone = ZoneId.systemDefault()
    private val categories = listOf(
        Category(1L, 1L, "工程问题"),
        Category(2L, 1L, "行程问题"),
        Category(3L, 1L, "排列组合"),
    )

    private fun <T> metric(value: T?, level: ConfidenceLevel, q: Int, sessions: Int, unit: String = "比例") =
        Metric(value, level, q, sessions, unit, if (value == null) "样本不足" else null)

    private fun stats(
        id: Long,
        subject: Long = 1L,
        accuracy: Double? = null,
        questions: Int = 0,
        sessions: Int = 0,
        speed: Double? = null,
        speedLevel: ConfidenceLevel = ConfidenceLevel.INSUFFICIENT,
        daysSince: Long? = null,
        trend: Double? = null,
        volatility: Double? = null,
        historical: Int = sessions,
        wilson: Double? = accuracy,
    ) = CategoryStats(
        categoryId = id,
        subjectId = subject,
        accuracy = metric(accuracy, Thresholds.confidence(questions, sessions), questions, sessions),
        secondsPerQuestion = metric(speed, speedLevel, questions, sessions, "秒/题"),
        trend = metric(trend, if (sessions >= 3) ConfidenceLevel.TRUSTED else ConfidenceLevel.INSUFFICIENT, questions, sessions),
        volatility = metric(volatility, if (sessions >= 3) ConfidenceLevel.TRUSTED else ConfidenceLevel.INSUFFICIENT, questions, sessions),
        sessionIds = (1..sessions).map { it.toLong() },
        wilsonLowerBound = metric(wilson, Thresholds.confidence(questions, sessions), questions, sessions),
        sampleSufficiency = metric(null, ConfidenceLevel.INSUFFICIENT, 0, 0),
        netDuration = metric(null, ConfidenceLevel.INSUFFICIENT, 0, 0, "秒"),
        speedStability = metric(null, ConfidenceLevel.INSUFFICIENT, 0, 0),
        practiceIntervals = metric(null, ConfidenceLevel.INSUFFICIENT, 0, 0, "天"),
        sessionStats = emptyList(),
        lastPracticedDate = daysSince?.let { today.minusDays(it) },
        daysSinceLastPractice = daysSince,
        historicalSessionIds = (1..historical).map { it.toLong() },
        forgettingEstimate = metric(null, ConfidenceLevel.INSUFFICIENT, 0, 0, "未定义"),
        marginalBenefit = metric(null, ConfidenceLevel.INSUFFICIENT, 0, 0, "未定义"),
    )

    private fun baseline(subject: Long = 1L, seconds: Double = 8.0) = mapOf(
        subject to SpeedBaseline(
            subjectId = subject,
            secondsPerQuestion = metric(seconds, ConfidenceLevel.TRUSTED, 40, 4, "秒/题"),
            categoryIds = listOf(1L, 2L, 3L),
            sessionIds = emptyList(),
        ),
    )

    private fun session(total: Int, minutes: Int = 0, date: LocalDate = today): Session {
        val start = date.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
        return Session(
            id = total.toLong() * 10,
            categoryId = 1L,
            subjectId = 1L,
            startAt = start,
            durationSec = minutes * 60,
            totalCount = total,
            correctCount = total / 2,
        )
    }

    private fun plan(
        stats: List<CategoryStats>,
        settings: UserSettings = UserSettings(),
        baselines: Map<Long, SpeedBaseline> = emptyMap(),
        sessions: List<Session> = emptyList(),
        exam: ExamTarget = ExamTarget(),
        active: Set<Long> = emptySet(),
    ) = PrescriptionPlanner.plan(
        categoryStats = stats,
        speedBaselines = baselines,
        categories = categories,
        sessions = sessions,
        settings = settings,
        exam = exam,
        today = today,
        attribution = DateAttribution.START,
        activeCategoryIds = active,
        zoneId = zone,
    )

    @Test
    fun noGoalGivesSingleVoluntaryBaseline() {
        val result = plan(listOf(stats(1L)))
        assertEquals(1, result.prescriptions.size)
        val only = result.prescriptions.single()
        assertEquals(PrescriptionType.BASELINE, only.type)
        assertEquals(10, only.targetCount)
        assertNull(only.targetMinutes)
        assertTrue(result.note!!.contains("每日题量目标"))
        assertNull(result.remainingQuestions)
        assertEquals(today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1, only.dueAt)
    }

    @Test
    fun questionBudgetCapsQuestionTotalAndCount() {
        val result = plan(
            listOf(stats(1L), stats(2L), stats(3L)),
            settings = UserSettings(dailyQuestionGoal = 30),
        )
        assertEquals(2, result.prescriptions.size)
        assertEquals(20, result.prescriptions.sumOf { it.targetCount })
        assertTrue(result.prescriptions.all { it.type == PrescriptionType.BASELINE })
        assertEquals(30, result.remainingQuestions)
        assertNull(result.note)
    }

    @Test
    fun spentBudgetLeavesNothingToPrescribe() {
        val result = plan(
            listOf(stats(1L)),
            settings = UserSettings(dailyQuestionGoal = 30),
            sessions = listOf(session(total = 25)),
        )
        assertTrue(result.isEmpty)
        assertTrue(result.note!!.contains("只剩 5 题"))
        assertEquals(5, result.remainingQuestions)
    }

    @Test
    fun urgentExamOnlyReviewsTrustedCategories() {
        val result = plan(
            listOf(
                stats(1L, accuracy = 0.8, questions = 40, sessions = 4, daysSince = 1, wilson = 0.7),
                stats(2L),
            ),
            settings = UserSettings(dailyQuestionGoal = 40),
            exam = ExamTarget(type = ExamType.CIVIL_SERVANT, examDate = today.plusDays(10)),
        )
        assertEquals(1, result.prescriptions.size)
        val only = result.prescriptions.single()
        assertEquals(PrescriptionType.REVIEW, only.type)
        assertEquals(1L, only.categoryId)
        assertTrue(only.attribution.contains("还剩 10 天"))
    }

    @Test
    fun urgentExamWithoutTrustedSamplesExplainsInsteadOfInventing() {
        val result = plan(
            listOf(stats(1L, questions = 10, sessions = 1, daysSince = 1, historical = 1)),
            settings = UserSettings(dailyQuestionGoal = 20),
            exam = ExamTarget(type = ExamType.CIVIL_SERVANT, examDate = today.plusDays(5)),
        )
        assertTrue(result.isEmpty)
        assertTrue(result.note!!.contains("没有达到可信样本"))
        assertTrue(result.note!!.contains("还剩 5 天"))
    }

    @Test
    fun staleReviewComesBeforeSamplingBaseline() {
        val result = plan(
            listOf(
                stats(1L, questions = 10, sessions = 1, daysSince = 20, historical = 1),
                stats(2L),
            ),
            settings = UserSettings(dailyQuestionGoal = 30),
        )
        assertEquals(2, result.prescriptions.size)
        assertEquals(PrescriptionType.REVIEW, result.prescriptions[0].type)
        assertEquals(PrescriptionType.BASELINE, result.prescriptions[1].type)
    }

    @Test
    fun lowAccuracyPicksKnowledgeOrCarefulReadingBySpeed() {
        val slow = plan(
            listOf(stats(1L, accuracy = 0.6, questions = 40, sessions = 4, speed = 10.0, speedLevel = ConfidenceLevel.TRUSTED, daysSince = 1)),
            settings = UserSettings(dailyQuestionGoal = 20),
            baselines = baseline(),
        )
        assertEquals(PrescriptionType.KNOWLEDGE, slow.prescriptions.single().type)
        assertEquals(10, slow.prescriptions.single().targetCount)

        val fast = plan(
            listOf(stats(1L, accuracy = 0.6, questions = 40, sessions = 4, speed = 5.0, speedLevel = ConfidenceLevel.TRUSTED, daysSince = 1)),
            settings = UserSettings(dailyQuestionGoal = 20),
            baselines = baseline(),
        )
        val careful = fast.prescriptions.single()
        assertEquals(PrescriptionType.CAREFUL_READING, careful.type)
        assertEquals(20, careful.targetCount)
        assertEquals(2.0, careful.targetMinutes!!.toDouble(), 0.0) // 20 × 6 秒 = 120 秒
    }

    @Test
    fun slowTrustedCategoryGetsSpeedTarget() {
        val result = plan(
            listOf(stats(1L, accuracy = 0.9, questions = 40, sessions = 4, speed = 12.0, speedLevel = ConfidenceLevel.TRUSTED, daysSince = 1, trend = 0.0)),
            settings = UserSettings(dailyQuestionGoal = 20),
            baselines = baseline(),
        )
        val speed = result.prescriptions.single()
        assertEquals(PrescriptionType.SPEED, speed.type)
        assertTrue(speed.reason.contains("10.8 秒"))
        assertEquals(4.0, speed.targetMinutes!!.toDouble(), 0.0)
    }

    @Test
    fun masteredCategoryDoesNotGetPrescription() {
        val result = plan(
            listOf(stats(1L, accuracy = 0.9, questions = 40, sessions = 4, speed = 5.0, speedLevel = ConfidenceLevel.TRUSTED, daysSince = 1, trend = 0.0)),
            settings = UserSettings(dailyQuestionGoal = 20),
            baselines = baseline(),
        )
        assertTrue(result.isEmpty)
        assertTrue(result.note!!.contains("表现稳定"))
    }

    @Test
    fun minuteOnlyWithoutSpeedKeepsTimingOptional() {
        val result = plan(
            listOf(stats(1L)),
            settings = UserSettings(dailyMinuteGoal = 30),
        )
        val only = result.prescriptions.single()
        assertEquals(PrescriptionType.BASELINE, only.type)
        assertNull(only.targetMinutes)
        assertTrue(result.note!!.contains("还没有可靠速度"))
        assertEquals(30, result.remainingMinutes)
    }

    @Test
    fun minuteOnlyWithSpeedEstimatesTotalMinutes() {
        val result = plan(
            listOf(stats(1L, accuracy = 0.9, questions = 40, sessions = 4, speed = 12.0, speedLevel = ConfidenceLevel.TRUSTED, daysSince = 1, trend = 0.0)),
            settings = UserSettings(dailyMinuteGoal = 30),
            baselines = baseline(),
        )
        val only = result.prescriptions.single()
        assertEquals(PrescriptionType.SPEED, only.type)
        assertEquals(4, only.targetMinutes)
        assertTrue(result.note!!.contains("只有分钟目标"))
    }

    @Test
    fun activeCategoryIsSkipped() {
        val result = plan(
            listOf(stats(1L)),
            settings = UserSettings(dailyQuestionGoal = 20),
            active = setOf(1L),
        )
        assertTrue(result.isEmpty)
    }
}
