package com.tiji.studyclock

import com.tiji.studyclock.domain.model.RecordMode
import com.tiji.studyclock.domain.model.SessionKind
import com.tiji.studyclock.ui.sanitizeScore
import com.tiji.studyclock.ui.scoreInputError
import com.tiji.studyclock.ui.sessionResultLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v5 输入防线与展示文案的纯 JVM 回归：
 * 这些函数被结算页 / 补记页 / 记录卡共用，改动会同时影响三条录入路径。
 */
class V5UiFormatTest {

    @Test
    fun sanitizeScoreKeepsAtMostOneDecimal() {
        assertEquals("7.5", sanitizeScore("7.5"))
        assertEquals("7.5", sanitizeScore("7.5.3"))
        assertEquals("9.9", sanitizeScore("9.99"))
        assertEquals("12", sanitizeScore("12"))
        assertEquals("100.0", sanitizeScore("100.05"))
    }

    @Test
    fun sanitizeScoreDropsNonNumericCharacters() {
        assertEquals("75", sanitizeScore("7a5"))
        assertEquals("", sanitizeScore("abc"))
        assertEquals("0.5", sanitizeScore(".5"))
        assertEquals("12345", sanitizeScore("123456789"))
    }

    @Test
    fun scoreInputErrorRequiresBothScoresAndBounds() {
        assertEquals("得分需要填写，最多 1 位小数。", scoreInputError("作文", "", "10"))
        assertEquals("满分需要填写，最多 1 位小数。", scoreInputError("作文", "7.5", ""))
        assertTrue(scoreInputError("作文", "12", "10")!!.contains("得分必须在 0 到满分之间"))
        assertTrue(scoreInputError("x".repeat(21), "7.5", "10")!!.contains("20"))
    }

    @Test
    fun scoreInputErrorAcceptsValidScore() {
        assertNull(scoreInputError("", "7.5", "9.0"))
        assertNull(scoreInputError("大作文", "75", "100"))
        assertNull(scoreInputError("小题 1", "9.0", "9.0"))
    }

    @Test
    fun sessionResultLineKeepsThreeTracksSeparate() {
        assertEquals(
            "得分 7.5 / 9.0（83.3%）",
            sessionResultLine(RecordMode.SCORE, SessionKind.NORMAL, 1, 0, 75, 90),
        )
        assertEquals(
            "复做 5 题 · 过 3",
            sessionResultLine(RecordMode.COUNT, SessionKind.REDO, 5, 3, null, null),
        )
        assertEquals(
            "答对 8 · 80.0%",
            sessionResultLine(RecordMode.COUNT, SessionKind.NORMAL, 10, 8, null, null),
        )
    }

    @Test
    fun scoreResultNeverShowsZeroPercentForNotApplicableCorrectCount() {
        val line = sessionResultLine(RecordMode.SCORE, SessionKind.NORMAL, 1, 0, 0, 100)
        assertEquals("得分 0.0 / 10.0（0.0%）", line)
        // 「0 分」是真实得分，不是「答对 0 题」；文案里不得出现答对/正确率字样。
        assertTrue(!line.contains("答对"))
        assertTrue(!line.contains("正确率"))
    }

    @Test
    fun redoResultWinsOverModeWhenBothArePresent() {
        assertEquals(
            "复做 5 题 · 过 3",
            sessionResultLine(RecordMode.SCORE, SessionKind.REDO, 5, 3, 75, 90),
        )
    }
}
