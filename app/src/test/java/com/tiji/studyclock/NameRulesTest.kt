package com.tiji.studyclock

import com.tiji.studyclock.domain.model.NameRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NameRulesTest {
    @Test
    fun trimsAndCollapsesInnerSpaces() {
        assertEquals("资料 分析", NameRules.normalize("  资料   分析  "))
        assertNull(NameRules.error("  资料   分析  "))
    }

    @Test
    fun blankNameIsRejected() {
        assertNotNull(NameRules.error(""))
        assertNotNull(NameRules.error("   "))
    }

    @Test
    fun tooLongNameIsRejectedAtTheBoundary() {
        val justFits = "题".repeat(NameRules.MAX_LENGTH)
        val tooLong = "题".repeat(NameRules.MAX_LENGTH + 1)
        assertNull(NameRules.error(justFits))
        assertNotNull(NameRules.error(tooLong))
    }

    @Test
    fun newlinesAndControlCharactersAreRejected() {
        assertNotNull(NameRules.error("线性代数\n第二章"))
        assertNotNull(NameRules.error("线性\u0000代数"))
    }

    @Test
    fun ordinaryChineseAndEnglishNamesPass() {
        assertNull(NameRules.error("高数（上）"))
        assertNull(NameRules.error("CET-6 听力"))
        assertNull(NameRules.error("2027 国考 行测"))
    }
}
