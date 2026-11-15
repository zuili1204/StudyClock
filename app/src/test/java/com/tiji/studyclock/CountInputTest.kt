package com.tiji.studyclock

import com.tiji.studyclock.domain.model.CountInput
import org.junit.Assert.assertEquals
import org.junit.Test

class CountInputTest {
    @Test
    fun keepsDigitsOnlyAndDropsLettersPunctuation() {
        assertEquals("34", CountInput.sanitize("34shuju,lihai"))
        assertEquals("34", CountInput.sanitize("3 4"))
        assertEquals("", CountInput.sanitize("abc"))
        assertEquals("", CountInput.sanitize("，。！"))
    }

    @Test
    fun convertsFullWidthDigits() {
        assertEquals("34", CountInput.sanitize("３４"))
        assertEquals("2026", CountInput.sanitize("２０２６年"))
    }

    @Test
    fun capsAtFourDigitsByDefault() {
        assertEquals("9999", CountInput.sanitize("99999"))
        assertEquals("1234", CountInput.sanitize("12a34b56"))
    }

    @Test
    fun customLimitAppliesToDurationMinutes() {
        assertEquals("240", CountInput.sanitize("240min", 3))
        assertEquals("24", CountInput.sanitize("24", 3))
        assertEquals("7", CountInput.sanitize("7", 1))
    }

    @Test
    fun blankAndMixedWhitespaceCollapse() {
        assertEquals("", CountInput.sanitize("   "))
        assertEquals("1620", CountInput.sanitize(" 16 20 "))
    }
}
