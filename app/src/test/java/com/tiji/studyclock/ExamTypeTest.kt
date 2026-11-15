package com.tiji.studyclock

import com.tiji.studyclock.domain.model.ExamTarget
import com.tiji.studyclock.domain.model.ExamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamTypeTest {
    @Test
    fun unnamedTargetShowsTheTypeLabelInsteadOfAGenericName() {
        // 用户抱怨点：没起名时倒计时应该显示“考公”，而不是“我的考试”。
        assertEquals("考公", ExamTarget(type = ExamType.CIVIL_SERVANT).displayName)
        assertEquals("考研", ExamTarget(type = ExamType.POSTGRAD).displayName)
    }

    @Test
    fun otherTypeKeepsTheNeutralDefault() {
        assertEquals("我的考试", ExamTarget(type = ExamType.OTHER).displayName)
        assertEquals("我的考试", ExamTarget().displayName)
    }

    @Test
    fun customNameWinsOverTheType() {
        assertEquals("2027 国考", ExamTarget(type = ExamType.CIVIL_SERVANT, name = "2027 国考").displayName)
        assertEquals("我的 CPA 计划", ExamTarget(type = ExamType.OTHER, name = "我的 CPA 计划").displayName)
    }

    @Test
    fun keysRoundTripAndUnknownFallsBackToOther() {
        ExamType.entries.forEach { type ->
            assertEquals(type, ExamType.fromKey(type.key))
        }
        assertEquals(ExamType.OTHER, ExamType.fromKey("SOMETHING_ELSE"))
        assertEquals(ExamType.OTHER, ExamType.fromKey(null))
        assertEquals(ExamType.OTHER, ExamType.fromKey(""))
    }

    @Test
    fun keysAreUniqueAndStable() {
        val keys = ExamType.entries.map { it.key }
        assertEquals(keys.size, keys.distinct().size)
        // 库里的字符串是持久化契约，重命名会让旧记录掉回“自定义”。
        assertTrue(
            listOf("CIVIL_SERVANT", "POSTGRAD", "TEACHER_CERT", "COMPUTER", "CET", "IELTS", "OTHER").containsAll(keys),
        )
    }

    @Test
    fun commonExamTypesArePresent() {
        val labels = ExamType.entries.map { it.label }
        listOf("考公", "考研", "教资", "计算机等级", "四六级", "雅思", "自定义").forEach { expected ->
            assertTrue("缺少考试类型：$expected", labels.contains(expected))
        }
    }
}
