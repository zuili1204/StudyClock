package com.tiji.studyclock.data

import com.tiji.studyclock.domain.model.RecordMode
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionKind
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 写入防线：模式互斥、SCORE 的 correctCount=0 语义、REDO 的袋关联。 */
class SessionValidationModeTest {
    private fun base() = Session(
        id = 0, categoryId = 1, subjectId = 1, startAt = 1_000L,
        totalCount = 10, correctCount = 7,
    )

    @Test
    fun plainCountSessionPasses() {
        requireValidSessionFields(base())
        validateSession(base())
    }

    @Test
    fun countSessionCannotCarryScoreFields() {
        assertThrows(IllegalArgumentException::class.java) {
            requireValidSessionFields(base().copy(scoreTenths = 75, maxScoreTenths = 90))
        }
    }

    @Test
    fun scoreSessionNeedsBoundsAndZeroCorrect() {
        val scored = base().copy(
            mode = RecordMode.SCORE, correctCount = 0,
            scoreTenths = 75, maxScoreTenths = 90, scoreLabel = "大作文",
        )
        requireValidSessionFields(scored)
        assertThrows(IllegalArgumentException::class.java) {
            requireValidSessionFields(scored.copy(scoreTenths = 91))
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireValidSessionFields(scored.copy(correctCount = 3))
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireValidSessionFields(scored.copy(answerSequence = "1111111111"))
        }
    }

    @Test
    fun redoSessionNeedsBatchAndNormalSessionMustNotHaveOne() {
        val redo = base().copy(kind = SessionKind.REDO, batchId = 9, totalCount = 3, correctCount = 2)
        requireValidSessionFields(redo)
        assertThrows(IllegalArgumentException::class.java) {
            requireValidSessionFields(redo.copy(batchId = null, scoreTenths = null).copy(batchId = -1L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireValidSessionFields(base().copy(batchId = 9))
        }
    }

    @Test
    fun importedUnlinkedRedoRowIsAllowedButExplicitlyUnlinked() {
        val imported = base().copy(kind = SessionKind.REDO, batchId = null, totalCount = 3, correctCount = 2)
        requireValidSessionFields(imported)
        assertTrue(imported.scoreTenths == null)
    }
}
