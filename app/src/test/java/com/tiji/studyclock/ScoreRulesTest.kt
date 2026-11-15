package com.tiji.studyclock

import com.tiji.studyclock.domain.model.ScoreRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** D21：分数以 0.1 分为单位的整数存储，UI 只允许 1 位小数，不预置默认满分。 */
class ScoreRulesTest {

    @Test
    fun parsesOneDecimalIntoIntegerTenths() {
        assertEquals(75, ScoreRules.parseTenths("7.5"))
        assertEquals(90, ScoreRules.parseTenths("9.0"))
        assertEquals(325, ScoreRules.parseTenths("32.5"))
        assertEquals(1000, ScoreRules.parseTenths("100"))
        assertEquals(0, ScoreRules.parseTenths("0"))
        assertEquals(75, ScoreRules.parseTenths(" 7.5 "))
    }

    @Test
    fun rejectsIllegalScoreText() {
        listOf(null, "", "-1", "7.55", "1e2", "abc", "1001", "1000.1", "  ", "7.")
            .forEach { assertNull("应拒绝：$it", ScoreRules.parseTenths(it)) }
    }

    @Test
    fun formatsWithoutFloatingPointDrift() {
        assertEquals("7.5", ScoreRules.format(75))
        assertEquals("9.0", ScoreRules.format(90))
        assertEquals("0.0", ScoreRules.format(0))
        assertEquals("100.0", ScoreRules.format(1000))
        assertEquals("32.5", ScoreRules.format(325))
    }

    @Test
    fun rateIsDerivedAndNeverStored() {
        assertEquals(0.8, ScoreRules.rate(80, 100)!!, 1e-12)
        assertEquals(7.5 / 9.0, ScoreRules.rate(75, 90)!!, 1e-12)
        assertNull(ScoreRules.rate(null, 100))
        assertNull(ScoreRules.rate(80, null))
        assertNull(ScoreRules.rate(80, 0))
        assertNull(ScoreRules.rate(110, 100))
    }

    @Test
    fun scoreValidationCoversBoundsAndLabel() {
        assertNull(ScoreRules.error(75, 90, "大作文·第2篇"))
        assertNull(ScoreRules.error(0, 100, null))
        assertNotNull(ScoreRules.error(null, 90, null))
        assertNotNull(ScoreRules.error(75, null, null))
        assertNotNull(ScoreRules.error(75, 0, null))
        assertNotNull(ScoreRules.error(91, 90, null))
        assertNotNull(ScoreRules.error(-1, 90, null))
        assertNotNull(ScoreRules.error(75, ScoreRules.MAX_SCORE_TENTHS + 1, null))
        assertNotNull(ScoreRules.error(75, 90, "x".repeat(ScoreRules.MAX_LABEL_LENGTH + 1)))
    }

    @Test
    fun commonMaxScoresAreSuggestionsNotDefaults() {
        assertEquals(listOf(90, 150, 200, 250, 400, 1000), ScoreRules.COMMON_MAX_SCORES)
        ScoreRules.COMMON_MAX_SCORES.forEach { assertNull(ScoreRules.error(0, it, null)) }
    }
}
