package com.tiji.studyclock

import com.tiji.studyclock.data.validateSession
import com.tiji.studyclock.domain.model.RecordMode
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** D27：专注记录只允许「时长 + 科目类别」，任何做题字段都必须在写入前被拒绝。 */
class SessionValidationTest {
    private val focus = Session(
        id = 1, categoryId = 1, subjectId = 1, startAt = 0, endAt = 1_800_000,
        durationSec = 1800, totalCount = 0, correctCount = 0, mode = RecordMode.FOCUS,
    )

    private fun problem(block: () -> Unit): String = try {
        block()
        ""
    } catch (error: IllegalArgumentException) {
        error.message.orEmpty()
    }

    @Test
    fun validFocusRecordPasses() {
        assertEquals("", problem { validateSession(focus) })
    }

    @Test
    fun focusRejectsQuestionCounts() {
        assertEquals("专注记录不能填写题量", problem { validateSession(focus.copy(totalCount = 5)) })
        assertEquals("专注记录不能填写答对数", problem { validateSession(focus.copy(correctCount = 1)) })
    }

    @Test
    fun focusCannotBeARedoSession() {
        assertEquals("专注记录不能是错题复做", problem { validateSession(focus.copy(kind = SessionKind.REDO)) })
    }

    @Test
    fun focusRequiresPositiveDuration() {
        assertEquals("专注记录必须填写时长", problem { validateSession(focus.copy(durationSec = null)) })
        assertEquals("时长只能为空或正数", problem { validateSession(focus.copy(durationSec = 0)) })
    }

    @Test
    fun focusRejectsScoreSequenceAndTags() {
        assertEquals("专注记录不能带计分字段", problem { validateSession(focus.copy(scoreTenths = 75)) })
        assertEquals("专注记录不能带计分字段", problem { validateSession(focus.copy(maxScoreTenths = 100)) })
        assertEquals("专注记录不能填写对错序列", problem { validateSession(focus.copy(answerSequence = "1")) })
        assertEquals("专注记录不能填写错题关键词", problem { validateSession(focus.copy(wrongTags = "审题")) })
    }

    @Test
    fun focusCannotCarryABatch() {
        assertEquals("普通记录不能关联错题袋", problem { validateSession(focus.copy(batchId = 9)) })
    }

    /** 既有口径不能被放松：COUNT / SCORE 仍要求正题量。 */
    @Test
    fun countAndScoreStillRequireQuestions() {
        val count = focus.copy(mode = RecordMode.COUNT)
        assertEquals("题量必须大于 0", problem { validateSession(count) })
        val score = focus.copy(mode = RecordMode.SCORE, totalCount = 1, scoreTenths = 75, maxScoreTenths = 90)
        assertEquals("", problem { validateSession(score) })
    }
}
