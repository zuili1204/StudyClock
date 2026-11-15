package com.tiji.studyclock

import com.tiji.studyclock.domain.diagnose.InsightGenerator
import com.tiji.studyclock.domain.model.Category
import com.tiji.studyclock.domain.model.DateAttribution
import com.tiji.studyclock.domain.model.RecordMode
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.stats.RedoLedger
import com.tiji.studyclock.domain.stats.ScoreEngine
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** N7：只在投入时间、没有可检验输出时才提示，专注本身不被当成「学得好」。 */
class CrossInsightFocusTest {
    private val today = LocalDate.of(2026, 11, 15)
    private val zone = ZoneId.systemDefault()
    private val categories = listOf(Category(1, 1, "主旨概括"))

    private fun millis(day: Int): Long =
        today.minusDays(day.toLong()).atStartOfDay(zone).toInstant().toEpochMilli() + 10 * 3_600_000L

    private fun focus(id: Long, day: Int) = Session(
        id = id, categoryId = 1, subjectId = 1, startAt = millis(day),
        durationSec = 1800, totalCount = 0, correctCount = 0, mode = RecordMode.FOCUS,
    )

    private fun practice(id: Long, day: Int) = Session(
        id = id, categoryId = 1, subjectId = 1, startAt = millis(day),
        durationSec = 600, totalCount = 10, correctCount = 8, mode = RecordMode.COUNT,
    )

    private fun insights(sessions: List<Session>) = InsightGenerator.generateCrossMode(
        sessions = sessions,
        categories = categories,
        score = ScoreEngine.calculate(sessions, today, zone),
        redo = RedoLedger.metrics(emptyList(), emptyList(), today, zone),
        batches = emptyList(),
        attempts = emptyList(),
        today = today,
        attribution = DateAttribution.START,
        zoneId = zone,
    )

    @Test
    fun threeFocusSessionsWithoutAnyOutputTriggerN7() {
        val found = insights(listOf(focus(1, 0), focus(2, 2), focus(3, 4)))
        assertTrue(found.any { it.id == "N7" })
    }

    @Test
    fun twoFocusSessionsAreNotEnough() {
        val found = insights(listOf(focus(1, 0), focus(2, 2)))
        assertFalse(found.any { it.id == "N7" })
    }

    @Test
    fun onePracticeRecordSilencesN7() {
        val found = insights(listOf(focus(1, 0), focus(2, 2), focus(3, 4), practice(4, 1)))
        assertFalse(found.any { it.id == "N7" })
    }

    /** 窗口外的练习不算「近 7 天有输出」。 */
    @Test
    fun practiceOutsideTheSevenDayWindowDoesNotCount() {
        val found = insights(listOf(focus(1, 0), focus(2, 2), focus(3, 4), practice(4, 20)))
        assertTrue(found.any { it.id == "N7" })
    }
}
