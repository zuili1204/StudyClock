package com.tiji.studyclock

import com.tiji.studyclock.domain.model.Category
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionSource
import com.tiji.studyclock.domain.stats.ConfidenceLevel
import com.tiji.studyclock.domain.stats.MetricId
import com.tiji.studyclock.domain.stats.StatsEngine
import com.tiji.studyclock.domain.stats.StatsSnapshot
import com.tiji.studyclock.domain.stats.Thresholds
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/** Synthetic fixtures only; numeric expectations are independent, fixed hand-worked examples. */
class StatsEngineTest {
    private val today = LocalDate.of(2026, 10, 28)
    private val utc = ZoneId.of("UTC")
    private val categories = listOf(Category(1, 1, "测试类别"))

    private fun record(
        id: Long,
        total: Int = 10,
        correct: Int = 8,
        daysAgo: Long = 0,
        seconds: Int? = null,
        category: Long = 1,
        source: SessionSource = SessionSource.MANUAL,
        hour: Int = 10,
    ): Session = Session(
        id = id, categoryId = category, subjectId = 1,
        startAt = today.minusDays(daysAgo).atTime(hour, 0).atZone(utc).toInstant().toEpochMilli(),
        durationSec = seconds, totalCount = total, correctCount = correct, source = source,
        createdAt = 0, updatedAt = 0,
    )

    private fun stats(vararg records: Session): StatsSnapshot = StatsEngine.calculate(records.toList(), categories, today, utc)
    private fun near(expected: Double, actual: Double?) {
        assertNotNull(actual)
        assertEquals(expected, actual!!, 1e-9)
    }
    private fun timedCategory(id: Long, speed: Int, subject: Long = 1): List<Session> = (1L..3L).map {
        record(id * 10 + it, seconds = speed * 10, category = id, daysAgo = 3 - it).copy(subjectId = subject)
    }

    @Test fun emptyInputReturnsNullMetricsRatherThanInventedZero() {
        val result = stats()
        assertEquals(0, result.totalQuestions)
        assertEquals(0, result.totalSessions)
        assertEquals(0, result.practicedCategories)
        assertNull(result.accuracy.value)
        assertNull(result.secondsPerQuestion.value)
        assertNull(result.timedSeconds)
        assertNull(result.coverage.value)
        assertNull(result.wilsonLowerBound.value)
        assertNull(result.sampleSufficiency.value)
        assertNull(result.timeDistribution.value)
        assertNull(result.goldenHour.value)
        assertEquals(ConfidenceLevel.INSUFFICIENT, result.accuracy.level)
        assertNotNull(result.accuracy.unavailableReason)
        assertTrue(result.sessionIds.isEmpty())
    }

    @Test fun catalogCategoryWithoutPracticeHasNoSeriesOrTiming() {
        val category = stats().categoryStats.single()
        assertEquals(1L, category.categoryId)
        assertNull(category.accuracy.value)
        assertNull(category.trend.value)
        assertNull(category.volatility.value)
        assertNull(category.speedStability.value)
        assertNull(category.practiceIntervals.value)
        assertNull(category.lastPracticedDate)
    }

    @Test fun oneSessionIsRawDescriptionEvenWithThirtyQuestions() {
        val result = stats(record(1, total = 30, correct = 24))
        near(0.8, result.sessionStats.single().accuracy.value)
        assertEquals(ConfidenceLevel.INSUFFICIENT, result.sessionStats.single().accuracy.level)
        near(1.0, result.sampleSufficiency.value)
        assertEquals(ConfidenceLevel.INSUFFICIENT, result.sampleSufficiency.level)
        assertNull(result.categoryStats.single().trend.value)
    }

    @Test fun allWrongIsARealZeroNotMissingData() {
        val result = stats(record(1, correct = 0))
        near(0.0, result.accuracy.value)
        near(0.0, result.wilsonLowerBound.value)
        assertNull(result.accuracy.unavailableReason)
    }

    @Test fun accuracyUsesQuestionWeightsNotMeanOfSessionPercentages() {
        val result = stats(record(1, total = 1, correct = 1), record(2, total = 9, correct = 0))
        near(0.1, result.accuracy.value)
        near(0.1, result.categoryStats.single().accuracy.value)
        near(1.0, result.sessionStats[0].accuracy.value)
        near(0.0, result.sessionStats[1].accuracy.value)
    }

    @Test fun untimedQuestionsAffectAccuracyButNotSpeedOrTimingConfidence() {
        val result = stats(record(1, seconds = 100), record(2, total = 30, correct = 15, seconds = 900),
            record(3, total = 100, correct = 50))
        near(0.5214285714285715, result.accuracy.value)
        near(25.0, result.secondsPerQuestion.value)
        assertEquals(1000L, result.timedSeconds)
        assertEquals(140, result.totalQuestions)
        assertEquals(40, result.secondsPerQuestion.questionCount)
        assertEquals(2, result.secondsPerQuestion.sessionCount)
        assertEquals(ConfidenceLevel.TRUSTED, result.accuracy.level)
        assertEquals(ConfidenceLevel.REFERENCE, result.secondsPerQuestion.level)
        assertEquals(40, result.netDuration.questionCount)
        assertEquals(2, result.netDuration.sessionCount)
    }

    @Test fun missingDurationDoesNotUseEndMinusStart() {
        val original = record(1)
        val result = stats(original.copy(endAt = original.startAt + 600_000))
        assertNull(result.timedSeconds)
        assertNull(result.secondsPerQuestion.value)
        assertEquals(0, result.secondsPerQuestion.questionCount)
    }

    @Test fun zeroAndNegativeDurationsAreExcludedOnlyFromTiming() {
        val result = stats(record(1, seconds = 0), record(2, seconds = -10), record(3, seconds = 200))
        assertEquals(30, result.totalQuestions)
        assertEquals(3, result.totalSessions)
        assertEquals(200L, result.timedSeconds)
        near(20.0, result.secondsPerQuestion.value)
        assertEquals(10, result.secondsPerQuestion.questionCount)
        assertEquals(ConfidenceLevel.INSUFFICIENT, result.secondsPerQuestion.level)
    }

    @Test fun noPositiveDurationMeansNullNotZeroNetSeconds() {
        val result = stats(record(1, seconds = 0), record(2, seconds = -1))
        assertNull(result.timedSeconds)
        assertNull(result.secondsPerQuestion.value)
    }

    @Test fun pausedSecondsAreNotSubtractedTwice() {
        val result = stats(record(1, seconds = 100).copy(pausedSec = 40, pauseCount = 2))
        assertEquals(100L, result.timedSeconds)
        near(10.0, result.secondsPerQuestion.value)
    }

    @Test fun netSecondsAreSummedAsLong() {
        val result = stats(record(1, seconds = Int.MAX_VALUE), record(2, seconds = Int.MAX_VALUE))
        assertEquals(4294967294L, result.timedSeconds)
    }

    @Test fun invalidQuestionCountsDoNotContaminateAnyMetric() {
        val result = stats(record(1, total = 0, correct = 0), record(2, correct = -1),
            record(3, correct = 11), record(4))
        assertEquals(listOf(4L), result.sessionIds)
        assertEquals(10, result.totalQuestions)
        near(0.8, result.accuracy.value)
    }

    @Test fun thirtyQuestionsAndThreeSessionsAreTrusted() {
        assertEquals(ConfidenceLevel.TRUSTED, Thresholds.confidence(30, 3))
        assertEquals(ConfidenceLevel.TRUSTED, stats(record(1), record(2), record(3)).accuracy.level)
    }

    @Test fun twentyNineQuestionsAndThreeSessionsAreReference() {
        assertEquals(ConfidenceLevel.REFERENCE, Thresholds.confidence(29, 3))
    }

    @Test fun thirtyQuestionsAndTwoSessionsRemainReference() {
        assertEquals(ConfidenceLevel.REFERENCE, Thresholds.confidence(30, 2))
    }

    @Test fun fifteenQuestionsAndTwoSessionsMeetReferenceBoundary() {
        assertEquals(ConfidenceLevel.REFERENCE, Thresholds.confidence(15, 2))
    }

    @Test fun fourteenQuestionsAreInsufficientDespiteManySessions() {
        assertEquals(ConfidenceLevel.INSUFFICIENT, Thresholds.confidence(14, 3))
    }

    @Test fun oneSessionNeverMeetsReferenceRegardlessOfQuestionCount() {
        assertEquals(ConfidenceLevel.INSUFFICIENT, Thresholds.confidence(1000, 1))
    }

    @Test fun sampleSufficiencyDoesNotReplaceSessionGate() {
        near(0.5, stats(record(1, total = 15)).sampleSufficiency.value)
        val full = stats(record(1, total = 100))
        near(1.0, full.sampleSufficiency.value)
        assertEquals(ConfidenceLevel.INSUFFICIENT, full.sampleSufficiency.level)
    }

    @Test fun wilsonLowerBoundMatchesIndependentAllCorrectExample() {
        near(0.886482908609522, stats(record(1, total = 30, correct = 30)).wilsonLowerBound.value)
    }

    @Test fun wilsonLowerBoundMatchesIndependentHalfCorrectExample() {
        near(0.2365895936154873, stats(record(1, correct = 5)).wilsonLowerBound.value)
    }

    @Test fun thirtyDayWindowIncludesTodayAndDayTwentyNineButNotDayThirty() {
        val result = stats(record(1), record(2, daysAgo = 29), record(3, daysAgo = 30))
        assertEquals(LocalDate.of(2026, 9, 29), result.windowStart)
        assertEquals(listOf(2L, 1L), result.sessionIds)
        assertEquals(20, result.totalQuestions)
        assertEquals(3, result.coverage.sessionCount)
    }

    @Test fun futureDatesAreExcludedFromWindowAndHistoricalCoverage() {
        val result = stats(record(1, daysAgo = -1, category = 2), record(2))
        assertEquals(listOf(2L), result.sessionIds)
        assertEquals(1, result.practicedCategories)
        near(0.023255813953488372, result.coverage.value)
    }

    @Test fun futureOnlyInputIsUnavailable() {
        val result = stats(record(1, daysAgo = -1))
        assertEquals(0, result.totalSessions)
        assertNull(result.coverage.value)
        assertNull(result.accuracy.value)
    }

    @Test fun deviceZoneChangesFutureDateEligibilityAndHourGrouping() {
        val epoch = today.plusDays(1).atTime(0, 30).atZone(utc).toInstant().toEpochMilli()
        val input = listOf(record(1, source = SessionSource.TIMER).copy(startAt = epoch))
        assertEquals(0, StatsEngine.calculate(input, categories, today, utc).totalSessions)
        val pacific = StatsEngine.calculate(input, categories, today, ZoneId.of("America/Los_Angeles"))
        assertEquals(1, pacific.totalSessions)
        val slot = pacific.timeDistribution.value!!.single()
        assertEquals(17, slot.hour)
        assertEquals(DayOfWeek.WEDNESDAY, slot.dayOfWeek)
    }

    @Test fun crossingMidnightIsAttributedToStartDateAndHour() {
        val start = today.minusDays(1).atTime(23, 50).atZone(utc).toInstant().toEpochMilli()
        val result = stats(record(1, source = SessionSource.TIMER).copy(startAt = start, endAt = start + 1_200_000))
        assertEquals(today.minusDays(1), result.sessionStats.single().date)
        assertEquals(23, result.timeDistribution.value!!.single().hour)
        assertEquals(DayOfWeek.TUESDAY, result.timeDistribution.value!!.single().dayOfWeek)
    }

    @Test fun practiceIntervalsUseLocalCalendarDaysAcrossDstNotElapsedHours() {
        val zone = ZoneId.of("America/New_York")
        val first = LocalDate.of(2026, 3, 7).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val second = LocalDate.of(2026, 3, 9).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val result = StatsEngine.calculate(listOf(record(2).copy(startAt = second), record(1).copy(startAt = first)),
            categories, LocalDate.of(2026, 3, 9), zone)
        assertEquals(listOf(2L), result.categoryStats.single().practiceIntervals.value)
    }

    @Test fun sameDayIntervalIsZeroAndIntervalsAreChronologicallySorted() {
        val result = stats(record(3), record(2, daysAgo = 3, hour = 11), record(1, daysAgo = 3, hour = 9))
        assertEquals(listOf(0L, 3L), result.categoryStats.single().practiceIntervals.value)
        assertEquals(listOf(1L, 2L, 3L), result.categoryStats.single().sessionIds)
    }

    @Test fun trendUsesPracticeIndexNotElapsedDays() {
        val result = stats(record(3, correct = 6), record(1, correct = 2, daysAgo = 20), record(2, correct = 4, daysAgo = 19))
        near(0.2, result.categoryStats.single().trend.value)
    }

    @Test fun accuracyVolatilityIsPopulationNotSampleStandardDeviation() {
        val result = stats(record(1, correct = 0), record(2, correct = 5), record(3, correct = 10))
        near(0.408248290463863, result.categoryStats.single().volatility.value)
        near(0.5, result.categoryStats.single().trend.value)
    }

    @Test fun seriesNeedThreeSessionsWithinSameCategory() {
        val result = stats(record(1), record(2), record(3, category = 2), record(4, category = 2))
        assertTrue(result.categoryStats.all { it.trend.value == null && it.volatility.value == null })
    }

    @Test fun threeTinySessionsHaveNumericSeriesButInsufficientConfidence() {
        val result = stats(record(1, total = 1, correct = 0), record(2, total = 1, correct = 1), record(3, total = 1, correct = 1))
        near(0.5, result.categoryStats.single().trend.value)
        assertEquals(ConfidenceLevel.INSUFFICIENT, result.categoryStats.single().trend.level)
    }

    @Test fun constantSeriesProducesRealZeroTrendAndVolatility() {
        val result = stats(record(1), record(2), record(3)).categoryStats.single()
        near(0.0, result.trend.value)
        near(0.0, result.volatility.value)
    }

    @Test fun speedStabilityUsesTimedSessionRatesAndPopulationDeviation() {
        val result = stats(record(1, seconds = 100), record(2, seconds = 200), record(3, seconds = 300),
            record(4, total = 100)).categoryStats.single()
        near(0.408248290463863, result.speedStability.value)
        assertEquals(30, result.speedStability.questionCount)
        assertEquals(3, result.speedStability.sessionCount)
    }

    @Test fun twoTimedSessionsCannotBorrowThirdUntimedSessionForStability() {
        val result = stats(record(1, seconds = 100), record(2, seconds = 200), record(3)).categoryStats.single()
        assertNull(result.speedStability.value)
        assertEquals(2, result.speedStability.sessionCount)
        assertEquals(ConfidenceLevel.INSUFFICIENT, result.speedStability.level)
    }

    @Test fun reversedInputsAndIdenticalStartTimestampsHaveStableIdOrdering() {
        val input = listOf(record(3, correct = 7), record(1, correct = 5), record(2, correct = 6))
        val result = StatsEngine.calculate(input, categories, today, utc)
        assertEquals(result, StatsEngine.calculate(input.reversed(), categories, today, utc))
        assertEquals(listOf(1L, 2L, 3L), result.sessionIds)
        near(0.1, result.categoryStats.single().trend.value)
    }

    @Test fun unsavedIdenticalIdsStillHaveDeterministicSeriesOrdering() {
        val input = listOf(record(0, correct = 7), record(0, correct = 5), record(0, correct = 6))
        assertEquals(StatsEngine.calculate(input, categories, today, utc),
            StatsEngine.calculate(input.reversed(), categories, today, utc))
    }

    @Test fun coverageUsesHistoricalFixedIdsIncludingArchivedAndRenamedCategories() {
        val catalog = listOf(Category(1, 1, "已改名", archived = true), Category(2, 1, "预置二"), Category(900, 1, "自定义"))
        val result = StatsEngine.calculate(listOf(record(1, daysAgo = 90), record(2, daysAgo = 31, category = 2),
            record(3, category = 900)), catalog, today, utc)
        near(0.046511627906976744, result.coverage.value)
        assertEquals(2, result.practicedCategories)
        assertEquals(1, result.customPracticedCategories)
        assertEquals(3, result.totalPracticedCategories)
        assertEquals(1, result.totalSessions)
        assertEquals(2, result.coverage.sessionCount)
        assertEquals(90L, result.categoryStats.first().daysSinceLastPractice)
    }

    @Test fun addingCustomCatalogEntriesCannotEnlargeCoverageDenominator() {
        val records = listOf(record(1))
        val expanded = categories + (100L..150L).map { Category(it, 1, "自定义$it") }
        val result = StatsEngine.calculate(records, expanded, today, utc)
        near(0.023255813953488372, result.coverage.value)
        assertEquals(1, result.practicedCategories)
    }

    @Test fun presetIdentityDoesNotDependOnCatalogMembershipOrName() {
        val result = StatsEngine.calculate(listOf(record(1, category = 43), record(2, category = 44)),
            listOf(Category(44, 1, "政治常识")), today, utc)
        assertEquals(1, result.practicedCategories)
        assertEquals(1, result.customPracticedCategories)
        near(0.023255813953488372, result.coverage.value)
    }

    @Test fun customOnlyHistoryEstablishesZeroPresetCoverage() {
        val result = stats(record(1, category = 900))
        near(0.0, result.coverage.value)
        assertEquals(0, result.coverage.questionCount)
        assertEquals(1, result.customPracticedCategories)
    }

    @Test fun manualWithoutClockNeverContributesToHourMetrics() {
        // 契约（v0.4 修订）：补记只有在填写了起止时刻时才有时段信号；只有日期锚点时必须排除。
        val manual = record(1, total = 50, seconds = 500)
        val result = stats(manual)
        assertNull(result.timeDistribution.value)
        assertNull(result.goldenHour.value)
        near(10.0, result.secondsPerQuestion.value)
    }

    @Test fun manualWithExplicitClockContributesToHourMetrics() {
        // 用户在补记里写了 10:00–10:08，就应当和计时记录一样进入时段统计。
        val manual = record(1, total = 16, seconds = 500)
        val result = stats(manual.copy(endAt = manual.startAt + 500_000))
        val slot = result.timeDistribution.value!!.single()
        assertEquals(10, slot.hour)
        assertEquals(16, slot.questionCount)
        assertEquals(16, result.timeDistribution.questionCount)
        // 单次 16 题不足以给出黄金时段，仍需 30 题 / 3 次。
        assertNull(result.goldenHour.value)
    }

    @Test fun timerStartContributesQuestionCountsEvenWithoutDuration() {
        val result = stats(record(1, total = 15, source = SessionSource.TIMER), record(2, source = SessionSource.TIMER))
        val slot = result.timeDistribution.value!!.single()
        assertEquals(25, slot.questionCount)
        assertEquals(2, slot.sessionCount)
        assertEquals(25, result.timeDistribution.questionCount)
        assertNull(result.timedSeconds)
    }

    @Test fun importNeedsBothEndpointsForRealTimeGrouping() {
        val complete = record(1, source = SessionSource.IMPORT)
        val noEnd = record(2, source = SessionSource.IMPORT, seconds = 100)
        val reverse = record(3, source = SessionSource.IMPORT)
        val result = stats(complete.copy(endAt = complete.startAt + 1_000), noEnd,
            reverse.copy(endAt = reverse.startAt - 1_000))
        assertEquals(listOf(1L), result.timeDistribution.value!!.single().sessionIds)
        assertEquals(3, result.totalSessions)
    }

    @Test fun timeDistributionSortsByLocalWeekdayThenHour() {
        val result = stats(record(1, source = SessionSource.TIMER, hour = 18),
            record(2, daysAgo = 2, source = SessionSource.TIMER, hour = 10),
            record(3, source = SessionSource.TIMER, hour = 8))
        assertEquals(listOf(10, 8, 18), result.timeDistribution.value!!.map { it.hour })
        assertEquals(DayOfWeek.MONDAY, result.timeDistribution.value!!.first().dayOfWeek)
    }

    @Test fun goldenHourPoolsWeekdaysButIgnoresUnderqualifiedPerfectSession() {
        val records = (1L..3L).map { record(it, correct = 7, daysAgo = it - 1, source = SessionSource.TIMER, hour = 8) } +
            (4L..6L).map { record(it, correct = 9, daysAgo = it - 4, source = SessionSource.TIMER, hour = 9) } +
            record(7, correct = 10, source = SessionSource.TIMER, hour = 6)
        val result = StatsEngine.calculate(records, categories, today, utc)
        assertEquals(9, result.goldenHour.value)
        assertEquals(30, result.goldenHour.questionCount)
        assertEquals(3, result.goldenHour.sessionCount)
        near(0.9, result.hourStats.single { it.hour == 9 }.accuracy.value)
    }

    @Test fun goldenHourUsesWeightedAccuracyRatherThanSessionMean() {
        val first = listOf(record(1, total = 1, correct = 1, source = SessionSource.TIMER, hour = 8),
            record(2, total = 9, correct = 9, source = SessionSource.TIMER, hour = 8),
            record(3, total = 20, correct = 0, source = SessionSource.TIMER, hour = 8))
        val second = (4L..6L).map { record(it, correct = 5, source = SessionSource.TIMER, hour = 9) }
        assertEquals(9, StatsEngine.calculate(first + second, categories, today, utc).goldenHour.value)
    }

    @Test fun goldenHourTieChoosesEarlierHourDeterministically() {
        val records = (1L..3L).map { record(it, source = SessionSource.TIMER, hour = 18) } +
            (4L..6L).map { record(it, source = SessionSource.TIMER, hour = 8) }
        assertEquals(8, StatsEngine.calculate(records.reversed(), categories, today, utc).goldenHour.value)
    }

    @Test fun goldenHourRequiresBothQuestionAndSessionBoundaries() {
        val records = listOf(record(1, source = SessionSource.TIMER, hour = 8), record(2, source = SessionSource.TIMER, hour = 8),
            record(3, total = 9, source = SessionSource.TIMER, hour = 8),
            record(4, total = 15, source = SessionSource.TIMER, hour = 9), record(5, total = 15, source = SessionSource.TIMER, hour = 9))
        assertNull(StatsEngine.calculate(records, categories, today, utc).goldenHour.value)
    }

    @Test fun speedBaselineIsMedianOfCategoryWeightedSpeeds() {
        val catalog = (1L..3L).map { Category(it, 1, "测试$it") }
        val result = StatsEngine.calculate(timedCategory(1, 10) + timedCategory(2, 20) + timedCategory(3, 90), catalog, today, utc)
        near(20.0, result.speedBaselines.getValue(1).secondsPerQuestion.value)
        assertEquals(90, result.speedBaselines.getValue(1).secondsPerQuestion.questionCount)
        assertEquals(9, result.speedBaselines.getValue(1).secondsPerQuestion.sessionCount)
    }

    @Test fun evenCategoryCountUsesMeanOfTwoMiddleSpeeds() {
        val catalog = (1L..4L).map { Category(it, 1, "测试$it") }
        val result = StatsEngine.calculate(timedCategory(1, 10) + timedCategory(2, 20) + timedCategory(3, 30) + timedCategory(4, 100),
            catalog, today, utc)
        near(25.0, result.speedBaselines.getValue(1).secondsPerQuestion.value)
    }

    @Test fun speedBaselineDoesNotBorrowFromAnotherSubject() {
        val catalog = listOf(Category(1, 1, "甲"), Category(2, 1, "乙"), Category(3, 2, "丙"))
        val result = StatsEngine.calculate(timedCategory(1, 10) + timedCategory(2, 20) + timedCategory(3, 30, subject = 2), catalog, today, utc)
        assertNull(result.speedBaselines.getValue(1).secondsPerQuestion.value)
        assertNull(result.speedBaselines.getValue(2).secondsPerQuestion.value)
    }

    @Test fun speedBaselineCannotBorrowUntimedConfidenceOrOldWindowRecords() {
        val catalog = (1L..4L).map { Category(it, 1, "测试$it") }
        val partial = timedCategory(3, 30).mapIndexed { index, record -> if (index == 2) record.copy(durationSec = null) else record }
        val old = timedCategory(4, 40).map { it.copy(startAt = today.minusDays(40).atStartOfDay(utc).toInstant().toEpochMilli()) }
        val result = StatsEngine.calculate(timedCategory(1, 10) + timedCategory(2, 20) + partial + old, catalog, today, utc)
        assertNull(result.speedBaselines.getValue(1).secondsPerQuestion.value)
        assertEquals(listOf(1L, 2L), result.speedBaselines.getValue(1).categoryIds)
    }

    @Test fun weakestRankingExcludesSmallSamplesInsteadOfPunishingWilsonUncertainty() {
        val records = listOf(record(1, total = 1, correct = 0), record(2, total = 7, correct = 0, category = 2),
            record(3, total = 8, correct = 0, category = 2)) + timedCategory(3, 10)
        val result = StatsEngine.calculate(records, emptyList(), today, utc)
        assertEquals(listOf(2L, 3L), result.weakestCategoryIds)
    }

    @Test fun trendThresholdIncludesBothEndpointsAsSteady() {
        val positive = stats(record(1, total = 100, correct = 80), record(2, total = 100, correct = 82), record(3, total = 100, correct = 84))
        val negative = stats(record(1, total = 100, correct = 84), record(2, total = 100, correct = 82), record(3, total = 100, correct = 80))
        near(0.02, positive.categoryStats.single().trend.value)
        near(-0.02, negative.categoryStats.single().trend.value)
        assertTrue(Thresholds.isSteady(positive.categoryStats.single().trend.value!!))
        assertTrue(Thresholds.isSteady(negative.categoryStats.single().trend.value!!))
        assertTrue(Thresholds.isRising(0.020001))
        assertTrue(Thresholds.isDeclining(-0.020001))
    }

    @Test fun speedThresholdsAreStrictAndVolatilityBoundaryIsInclusive() {
        assertFalse(Thresholds.isFast(8.0, 10.0))
        assertTrue(Thresholds.isFast(7.99, 10.0))
        assertFalse(Thresholds.isSlow(12.0, 10.0))
        assertTrue(Thresholds.isSlow(12.01, 10.0))
        assertTrue(Thresholds.isLargeVolatility(0.10))
        assertFalse(Thresholds.isLargeVolatility(0.0999))
    }

    @Test fun registerHasThirteenMvpMetricsAndDeferredMetricsNeverEstimate() {
        assertEquals(13, MetricId.values().count { it.implementedInMvp })
        assertFalse(MetricId.D3.implementedInMvp)
        assertFalse(MetricId.D4.implementedInMvp)
        val category = stats(record(1), record(2), record(3)).categoryStats.single()
        assertNull(category.forgettingEstimate.value)
        assertNull(category.marginalBenefit.value)
        assertTrue(category.forgettingEstimate.unavailableReason!!.contains("v2"))
        assertEquals(0, category.forgettingEstimate.questionCount)
    }
}
