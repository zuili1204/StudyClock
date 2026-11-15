package com.tiji.studyclock

import com.tiji.studyclock.domain.model.DateAttribution
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.stats.TagStats
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TagStatsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 10, 28)

    private fun session(
        id: Long,
        tags: String?,
        daysAgo: Long = 0,
        categoryId: Long = 1,
        total: Int = 10,
        correct: Int = 7,
        seconds: Int? = 300,
    ): Session {
        val date = today.minusDays(daysAgo)
        return Session(
            id = id, categoryId = categoryId, subjectId = 1,
            startAt = date.atTime(20, 0).atZone(zone).toInstant().toEpochMilli(),
            durationSec = seconds, totalCount = total, correctCount = correct, wrongTags = tags,
        )
    }

    @Test
    fun aggregatesPerTagAcrossMultipleSessionsAndCategories() {
        val stats = TagStats.summarize(
            listOf(
                session(1, "属性规律|增长率", daysAgo = 2),
                session(2, "属性规律", daysAgo = 0, categoryId = 2, total = 6, correct = 4, seconds = null),
                session(3, null),
            ),
            DateAttribution.START,
        )
        assertEquals(listOf("属性规律", "增长率"), stats.map { it.tag })
        val main = stats.first()
        assertEquals(2, main.sessionCount)
        assertEquals(16, main.questionCount)
        assertEquals(5, main.wrongCount)
        assertEquals(300L, main.netSeconds)
        assertEquals(listOf(1L, 2L), main.categoryIds)
        assertEquals(listOf(1L, 2L), main.sessionIds)
        assertEquals(today, main.lastUsed)
        assertEquals(11.0 / 16, main.accuracy!!, 1e-9)

        val second = stats[1]
        assertEquals(1, second.sessionCount)
        assertEquals(300L, second.netSeconds)
    }

    @Test
    fun ignoresBlankTagsAndSessionsOutsideTheWindow() {
        val stats = TagStats.summarize(
            listOf(session(1, "  "), session(2, "属性规律", daysAgo = 30), session(3, "增长率", daysAgo = 1)),
            DateAttribution.START,
            since = today.minusDays(13),
            until = today,
        )
        assertEquals(listOf("增长率"), stats.map { it.tag })
    }

    @Test
    fun tagsOnAllCorrectSessionsStillCountButShowNoWrongAnswers() {
        val stat = TagStats.summarize(listOf(session(1, "粗心", total = 10, correct = 10)), DateAttribution.START).single()
        assertEquals(0, stat.wrongCount)
        assertEquals(1.0, stat.accuracy!!, 1e-9)
    }

    @Test
    fun recentPrefersLatestUseOverAllTimeFrequency() {
        val stats = TagStats.summarize(
            listOf(
                session(1, "高频", daysAgo = 20),
                session(2, "高频", daysAgo = 19),
                session(3, "新词", daysAgo = 0),
            ),
            DateAttribution.START,
        )
        assertEquals(listOf("新词"), TagStats.recent(stats, 1).map { it.tag })
    }

    @Test
    fun emptyInputReturnsNoTags() {
        assertEquals(emptyList<String>(), TagStats.summarize(emptyList(), DateAttribution.START).map { it.tag })
        assertNull(
            TagStats.summarize(emptyList(), DateAttribution.START).firstOrNull(),
        )
    }
}
