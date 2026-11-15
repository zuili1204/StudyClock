package com.tiji.studyclock

import com.tiji.studyclock.domain.model.ClockInput
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClockInputTest {
    @Test
    fun parsesPlainAndFullWidthColons() {
        assertEquals(LocalTime.of(21, 16), ClockInput.parse("21:16")?.time)
        assertEquals(LocalTime.of(21, 16), ClockInput.parse("21：16")?.time)
        assertEquals(LocalTime.of(9, 5), ClockInput.parse(" 9:5 ")?.time)
    }

    @Test
    fun twentyFourMeansNextDayMidnight() {
        val parsed = ClockInput.parse("24:03")!!
        assertEquals(LocalTime.of(0, 3), parsed.time)
        assertEquals(1, parsed.dayOffset)
        assertEquals("24:03", ClockInput.format(parsed))
    }

    @Test
    fun rejectsMalformedOrOutOfRangeInput() {
        listOf("", "  ", "25:00", "21:60", "-1:10", "2116", "21:16:00", "ab:cd").forEach { raw ->
            assertNull("应拒绝：$raw", ClockInput.parse(raw))
        }
    }

    @Test
    fun durationSubtractsStartFromEnd() {
        val start = ClockInput.parse("21:16")!!
        val end = ClockInput.parse("21:26")!!
        assertEquals(600, ClockInput.durationSeconds(start, end))
    }

    @Test
    fun crossMidnightUsesTwentyFourNotation() {
        val start = ClockInput.parse("23:38")!!
        val end = ClockInput.parse("24:03")!!
        assertEquals(1_500, ClockInput.durationSeconds(start, end))
    }

    @Test
    fun endBeforeStartIsRejected() {
        val start = ClockInput.parse("21:26")!!
        val end = ClockInput.parse("21:16")!!
        assertNull(ClockInput.durationSeconds(start, end))
    }

    @Test
    fun startCannotBeNextDay() {
        val start = ClockInput.parse("24:10")!!
        val end = ClockInput.parse("24:30")!!
        assertNull(ClockInput.durationSeconds(start, end))
    }
}
