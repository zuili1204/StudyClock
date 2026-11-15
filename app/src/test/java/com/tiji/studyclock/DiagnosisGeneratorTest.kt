package com.tiji.studyclock

import com.tiji.studyclock.domain.diagnose.DiagnosisGenerator
import com.tiji.studyclock.domain.diagnose.Finding
import com.tiji.studyclock.domain.model.Category
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionSource
import com.tiji.studyclock.domain.stats.ConfidenceLevel
import com.tiji.studyclock.domain.stats.StatsEngine
import com.tiji.studyclock.domain.stats.StatsSnapshot
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/** Explicit synthetic rule scenarios, not reconstructed historical user records. */
class DiagnosisGeneratorTest {
    private val today = LocalDate.of(2026, 10, 28)
    private val utc = ZoneId.of("UTC")
    private val catalog = (1L..3L).map { Category(it, 1, "测试类别$it") }

    private fun series(
        category: Long = 1,
        rates: List<Int> = listOf(60, 60, 60),
        speed: Int? = 10,
        ages: List<Long> = rates.indices.map { (rates.lastIndex - it).toLong() },
    ): List<Session> = rates.mapIndexed { index, rate ->
        Session(
            id = category * 100 + index + 1,
            categoryId = category,
            subjectId = 1,
            startAt = today.minusDays(ages[index]).atTime(10, 0).atZone(utc).toInstant().toEpochMilli(),
            totalCount = 100,
            correctCount = rate,
            durationSec = speed?.times(100),
            source = SessionSource.TIMER,
            createdAt = 0,
            updatedAt = 0,
        )
    }

    private fun snapshot(
        primary: List<Session> = series(),
        peers: Boolean = true,
        categories: List<Category> = catalog,
    ): StatsSnapshot = StatsEngine.calculate(
        primary + if (peers) series(2, listOf(80, 80, 80)) + series(3, listOf(80, 80, 80)) else emptyList(),
        categories, today, utc,
    )

    private fun finding(result: StatsSnapshot): Finding = DiagnosisGenerator.generate(result, catalog).single { it.categoryId == 1L }

    @Test fun emptyWindowHasOneNonDiagnosticSamplingPrompt() {
        val result = DiagnosisGenerator.generate(snapshot(emptyList(), peers = false), catalog)
        assertEquals(1, result.size)
        assertEquals("NO_DATA", result.single().ruleId)
        assertEquals(ConfidenceLevel.INSUFFICIENT, result.single().confidence)
        assertTrue(result.single().evidenceIds.isEmpty())
        assertTrue(result.single().detail.contains("2026-09-29至2026-10-28"))
    }

    @Test fun futureOnlyRecordsCannotGenerateObservations() {
        val result = DiagnosisGenerator.generate(snapshot(series(ages = listOf(-3, -2, -1)), peers = false), catalog)
        assertEquals("NO_DATA", result.single().ruleId)
        assertTrue(result.single().evidenceIds.isEmpty())
    }

    @Test fun oneLargeSessionNeedsTwoMoreSessionsNotMoreQuestions() {
        val result = finding(snapshot(series(rates = listOf(10))))
        assertEquals("SAMPLE_INSUFFICIENT", result.ruleId)
        assertTrue(result.detail.contains("还需0题 / 2次"))
        assertEquals(listOf(101L), result.evidenceIds)
    }

    @Test fun manyTinySessionsNeedQuestionsEvenWithEnoughSessions() {
        val result = finding(snapshot(series().map { it.copy(totalCount = 4, correctCount = 2) }))
        assertEquals("SAMPLE_INSUFFICIENT", result.ruleId)
        assertTrue(result.detail.contains("还需18题 / 0次"))
        assertEquals(ConfidenceLevel.INSUFFICIENT, result.confidence)
    }

    @Test fun twentyNineQuestionsAndThreeSessionsDoNotDiagnose() {
        val records = series().mapIndexed { index, session -> session.copy(totalCount = if (index == 2) 9 else 10, correctCount = 6) }
        val result = finding(snapshot(records))
        assertEquals("SAMPLE_INSUFFICIENT", result.ruleId)
        assertTrue(result.detail.contains("还需1题 / 0次"))
        assertEquals(ConfidenceLevel.REFERENCE, result.confidence)
    }

    @Test fun fifteenQuestionsAndTwoSessionsReportBothDeficits() {
        val records = series(rates = listOf(60, 60)).mapIndexed { index, session ->
            session.copy(totalCount = if (index == 0) 7 else 8, correctCount = 4)
        }
        val result = finding(snapshot(records))
        assertEquals("SAMPLE_INSUFFICIENT", result.ruleId)
        assertTrue(result.detail.contains("还需15题 / 1次"))
        assertEquals(ConfidenceLevel.REFERENCE, result.confidence)
    }

    @Test fun exactThirtyQuestionThreeSessionBoundaryAllowsFacts() {
        val records = series().map { it.copy(totalCount = 10, correctCount = 6, durationSec = 100) }
        val result = finding(snapshot(records))
        assertEquals("LOW_ACCURACY", result.ruleId)
        assertEquals(ConfidenceLevel.TRUSTED, result.confidence)
    }

    @Test fun trustedLargeVolatilityIsFirstDirection() {
        val result = finding(snapshot(series(rates = listOf(50, 70, 90))))
        assertEquals("VOLATILE", result.ruleId)
        assertTrue(result.detail.contains("16.3个百分点"))
        assertEquals(listOf(101L, 102L, 103L), result.evidenceIds)
    }

    @Test fun volatilityAtExactlyPointOneIsLarge() {
        // Four equally sized samples .6,.8,.6,.8: population standard deviation is exactly .1.
        val result = finding(snapshot(series(rates = listOf(60, 80, 60, 80))))
        assertEquals("VOLATILE", result.ruleId)
        assertTrue(result.detail.contains("10.0个百分点"))
    }

    @Test fun volatileSmallSampleRemainsSamplingPrompt() {
        val input = series(rates = listOf(0, 100, 0)).map { it.copy(totalCount = 1, correctCount = if (it.correctCount == 100) 1 else 0) }
        assertEquals("SAMPLE_INSUFFICIENT", finding(snapshot(input)).ruleId)
    }

    @Test fun volatilityTakesPriorityOverStalenessAndMissingSpeed() {
        val result = finding(snapshot(series(rates = listOf(50, 70, 90), speed = null, ages = listOf(10, 9, 8))))
        assertEquals("VOLATILE", result.ruleId)
    }

    @Test fun highPastResultAndDeclineAfterLongGapStateFactsWithoutForgettingEstimate() {
        val result = finding(snapshot(series(rates = listOf(90, 87, 81), speed = null, ages = listOf(11, 10, 0))))
        assertEquals("GAP_DECLINE", result.ruleId)
        assertTrue(result.detail.contains("间隔10天"))
        assertTrue(result.action.contains("不能确定原因"))
        assertFalse(result.title.contains("遗忘"))
    }

    @Test fun exactlySevenDayIntervalDoesNotTriggerLongGapRule() {
        val result = finding(snapshot(series(rates = listOf(90, 87, 81), ages = listOf(8, 7, 0))))
        assertEquals("DECLINING", result.ruleId)
    }

    @Test fun eightDaysWithoutPracticeSuggestsRetestWithoutAbilityClaim() {
        val result = finding(snapshot(series(ages = listOf(10, 9, 8))))
        assertEquals("STALE", result.ruleId)
        assertTrue(result.detail.contains("距今8天"))
        assertTrue(result.detail.contains("不代表能力已下降"))
    }

    @Test fun exactlySevenDaysIsFresh() {
        val result = finding(snapshot(series(speed = 5, ages = listOf(9, 8, 7))))
        assertEquals("LOW_FAST", result.ruleId)
    }

    @Test fun missingTimingNeverBecomesFastOrSlowJudgment() {
        val result = finding(snapshot(series(speed = null)))
        assertEquals("DIMENSIONS_UNAVAILABLE", result.ruleId)
        assertTrue(result.detail.contains("计时子集距可信门槛还需30题 / 3次"))
    }

    @Test fun timingCannotBorrowThirdUntimedSessionConfidence() {
        val input = series().mapIndexed { index, session -> if (index == 2) session.copy(durationSec = null) else session }
        val result = finding(snapshot(input))
        assertEquals("DIMENSIONS_UNAVAILABLE", result.ruleId)
        assertTrue(result.detail.contains("计时子集距可信门槛还需0题 / 1次"))
    }

    @Test fun oneTrustedCategoryHasNoSpeedBaseline() {
        val result = finding(snapshot(series(speed = 1), peers = false))
        assertEquals("DIMENSIONS_UNAVAILABLE", result.ruleId)
        assertTrue(result.detail.contains("可信类别不足3个"))
    }

    @Test fun categoriesFromOtherSubjectsCannotEnableSpeedClaims() {
        val categories = listOf(Category(1, 1, "甲"), Category(2, 1, "乙"), Category(3, 2, "丙"))
        val records = series(speed = 1) + series(2) + series(3).map { it.copy(subjectId = 2) }
        val result = finding(StatsEngine.calculate(records, categories, today, utc))
        assertEquals("DIMENSIONS_UNAVAILABLE", result.ruleId)
    }

    @Test fun lowAndFastIsDescriptiveNotCarelessnessAttribution() {
        val result = finding(snapshot(series(speed = 5)))
        assertEquals("LOW_FAST", result.ruleId)
        assertEquals("正确率偏低，单题耗时较短", result.title)
        assertTrue(result.action.contains("不据快慢判断原因"))
        assertFalse(result.detail.contains("粗心"))
    }

    @Test fun lowAndSlowDoesNotInferKnowledgeGap() {
        val result = finding(snapshot(series(speed = 20)))
        assertEquals("LOW_SLOW", result.ruleId)
        assertTrue(result.action.contains("不据耗时推断知识缺口"))
    }

    @Test fun highSlowAndRisingIsObservationWithoutSpeedPrescription() {
        val result = finding(snapshot(series(rates = listOf(85, 88, 91), speed = 20)))
        assertEquals("HIGH_SLOW_RISING", result.ruleId)
        assertFalse(result.action.contains("目标秒"))
        assertFalse(result.action.contains("两组"))
    }

    @Test fun highFastSteadyDoesNotClaimMastery() {
        val result = finding(snapshot(series(rates = listOf(90, 90, 90), speed = 5)))
        assertEquals("HIGH_FAST_STEADY", result.ruleId)
        assertTrue(result.action.contains("不据此断言掌握程度"))
    }

    @Test fun accuracyAtPointSevenIsNotLow() {
        val result = finding(snapshot(series(rates = listOf(70, 70, 70), speed = 5)))
        assertEquals("OBSERVATION", result.ruleId)
    }

    @Test fun accuracyAtPointEightFiveIsHigh() {
        val result = finding(snapshot(series(rates = listOf(85, 85, 85), speed = 5)))
        assertEquals("HIGH_FAST_STEADY", result.ruleId)
    }

    @Test fun speedExactlyEightyPercentOfMedianIsNotFast() {
        val result = finding(snapshot(series(speed = 8)))
        assertEquals("LOW_ACCURACY", result.ruleId)
    }

    @Test fun speedExactlyOneHundredTwentyPercentOfMedianIsNotSlow() {
        val result = finding(snapshot(series(speed = 12)))
        assertEquals("LOW_ACCURACY", result.ruleId)
    }

    @Test fun risingSlopeExactlyPointZeroTwoIsStillSteady() {
        val result = finding(snapshot(series(rates = listOf(56, 58, 60), speed = 5)))
        assertEquals("LOW_FAST", result.ruleId)
    }

    @Test fun decliningSlopeExactlyMinusPointZeroTwoIsStillSteady() {
        val result = finding(snapshot(series(rates = listOf(60, 58, 56), speed = 5)))
        assertEquals("LOW_FAST", result.ruleId)
    }

    @Test fun highAndSlowWithoutRisingDoesNotUseRisingTemplate() {
        val result = finding(snapshot(series(rates = listOf(90, 90, 90), speed = 20)))
        assertEquals("HIGH_ACCURACY", result.ruleId)
    }

    @Test fun intermediateAccuracyDoesNotAcquireCausalLabelFromFastTiming() {
        val result = finding(snapshot(series(rates = listOf(75, 75, 75), speed = 5)))
        assertEquals("OBSERVATION", result.ruleId)
    }

    @Test fun trustedNegativeTrendReportsPracticeIndexedSlope() {
        val result = finding(snapshot(series(rates = listOf(80, 75, 70))))
        assertEquals("DECLINING", result.ruleId)
        assertTrue(result.detail.contains("-5.0个百分点/次"))
    }

    @Test fun trustedPositiveTrendReportsFactsWithoutCausalImprovementClaim() {
        val result = finding(snapshot(series(rates = listOf(70, 75, 80))))
        assertEquals("RISING", result.ruleId)
        assertTrue(result.detail.contains("5.0个百分点/次"))
        assertTrue(result.action.contains("暂不把变化归因"))
    }

    @Test fun atMostOneMainDirectionPerCategoryIsReturned() {
        val result = DiagnosisGenerator.generate(snapshot(series(rates = listOf(50, 70, 90), ages = listOf(10, 9, 8))), catalog)
        assertEquals(3, result.size)
        assertEquals(3, result.map { it.categoryId }.distinct().size)
        assertEquals("VOLATILE", result.single { it.categoryId == 1L }.ruleId)
    }

    @Test fun speedComparisonIncludesBaselineEvidenceButNotFutureOrOldRecords() {
        val primary = series(speed = 5) + series(rates = listOf(60), ages = listOf(40)).map { it.copy(id = 901) } +
            series(rates = listOf(60), ages = listOf(-1)).map { it.copy(id = 902) }
        val result = finding(snapshot(primary))
        assertEquals("LOW_FAST", result.ruleId)
        assertEquals(setOf(101L, 102L, 103L, 201L, 202L, 203L, 301L, 302L, 303L), result.evidenceIds.toSet())
        assertEquals(9, result.evidenceIds.size)
    }

    @Test fun findingIdsEvidenceAndOrderAreStableAcrossInputPermutations() {
        val input = series(speed = 5) + series(2) + series(3)
        val a = DiagnosisGenerator.generate(StatsEngine.calculate(input, catalog, today, utc), catalog)
        val b = DiagnosisGenerator.generate(StatsEngine.calculate(input.reversed(), catalog.reversed(), today, utc), catalog.reversed())
        assertEquals(a, b)
        assertEquals("LOW_FAST:1", a.first().id)
    }

    @Test fun categoryNamesCannotMakeTitlesExceedTwentyFiveCharacters() {
        val longNames = catalog.map { it.copy(name = "很长的自定义类别名称".repeat(8)) }
        val result = DiagnosisGenerator.generate(snapshot(series(speed = 5), categories = longNames), longNames)
        assertTrue(result.all { it.title.length <= 25 })
        assertTrue(result.first().detail.contains(longNames.first().name))
        assertFalse(result.first().title.contains(longNames.first().name))
    }

    @Test fun allFifteenTemplatesAreShortAndHaveActionsAndEvidenceWhereAvailable() {
        val scenarios = listOf(
            snapshot(emptyList(), peers = false),
            snapshot(series(rates = listOf(60))),
            snapshot(series(rates = listOf(50, 70, 90))),
            snapshot(series(rates = listOf(90, 87, 81), ages = listOf(11, 10, 0))),
            snapshot(series(ages = listOf(10, 9, 8))),
            snapshot(series(speed = null)),
            snapshot(series(speed = 5)),
            snapshot(series(speed = 20)),
            snapshot(series(rates = listOf(85, 88, 91), speed = 20)),
            snapshot(series(rates = listOf(90, 90, 90), speed = 5)),
            snapshot(series(rates = listOf(80, 75, 70))),
            snapshot(series(rates = listOf(70, 75, 80))),
            snapshot(series()),
            snapshot(series(rates = listOf(90, 90, 90))),
            snapshot(series(rates = listOf(75, 75, 75))),
        )
        val findings = scenarios.map { result ->
            if (result.totalSessions == 0) DiagnosisGenerator.generate(result, catalog).single() else finding(result)
        }
        assertEquals(15, findings.map { it.ruleId }.distinct().size)
        assertTrue(findings.all { it.title.isNotBlank() && it.title.length <= 25 })
        assertTrue(findings.all { it.detail.isNotBlank() && it.action.isNotBlank() })
        assertTrue(findings.filter { it.ruleId != "NO_DATA" }.all { it.evidenceIds.isNotEmpty() })
    }

    @Test fun recordWithoutCurrentCatalogEntryStillHasTraceableFinding() {
        val result = StatsEngine.calculate(series(), emptyList(), today, utc)
        val finding = DiagnosisGenerator.generate(result, emptyList()).single()
        assertTrue(finding.detail.startsWith("未命名类别："))
        assertEquals(listOf(101L, 102L, 103L), finding.evidenceIds)
    }

    @Test fun historyOutsideWindowDoesNotPretendCurrentAbilityIsZero() {
        val result = DiagnosisGenerator.generate(snapshot(series(ages = listOf(42, 41, 40)), peers = false), catalog)
        assertEquals("NO_DATA", result.single().ruleId)
        assertTrue(result.single().detail.contains("历史覆盖与当前窗口分开统计"))
        assertFalse(result.single().detail.contains("正确率0"))
    }
}
