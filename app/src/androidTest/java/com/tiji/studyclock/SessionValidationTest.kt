package com.tiji.studyclock

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tiji.studyclock.data.validateSession
import com.tiji.studyclock.domain.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionValidationTest {
    private val valid = Session(
        categoryId = 1, subjectId = 1, startAt = 1_000, endAt = 2_000,
        durationSec = 1, totalCount = 3, correctCount = 2, answerSequence = "101",
    )

    @Test
    fun validAndAbsentSequencesArePreserved() {
        assertSame(valid, validateSession(valid))
        assertNull(validateSession(valid.copy(answerSequence = null)).answerSequence)
        assertEquals("000", validateSession(valid.copy(correctCount = 0, answerSequence = "000")).answerSequence)
        assertEquals("111", validateSession(valid.copy(correctCount = 3, answerSequence = "111")).answerSequence)
    }

    @Test
    fun malformedSequencesAreDiscardedWithoutLosingOtherFields() {
        for (sequence in listOf("", "10", "1010", "1x1", "1 1", "111", "000", "１０１")) {
            val issues = mutableListOf<String>()
            val input = valid.copy(answerSequence = sequence)
            val result = validateSession(input) { issues.add(it) }
            assertEquals(valid.copy(answerSequence = null), result)
            assertEquals(1, issues.size)
        }
    }

    @Test
    fun invalidRequiredFieldsAreRejected() {
        val cases = listOf(
            valid.copy(totalCount = 0), valid.copy(totalCount = -1),
            valid.copy(correctCount = -1), valid.copy(correctCount = 4),
            valid.copy(durationSec = 0), valid.copy(durationSec = -1),
            valid.copy(pausedSec = -1), valid.copy(pauseCount = -1),
            valid.copy(difficulty = -1), valid.copy(difficulty = 4),
            valid.copy(energy = -1), valid.copy(energy = 4),
            valid.copy(endAt = 999),
        )
        cases.forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { validateSession(input) }
        }
    }

    @Test
    fun nullableTimingAndAssessmentBoundariesAreValid() {
        val manual = valid.copy(endAt = null, durationSec = null, answerSequence = null)
        assertEquals(manual, validateSession(manual))
        for (rating in 0..3) {
            val input = valid.copy(difficulty = rating, energy = rating, endAt = valid.startAt)
            assertEquals(input, validateSession(input))
        }
    }
}
