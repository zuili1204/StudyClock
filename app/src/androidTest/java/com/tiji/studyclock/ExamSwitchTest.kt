package com.tiji.studyclock

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tiji.studyclock.domain.model.ExamType
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExamSwitchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun useCivilServant() {
        grantNotificationPermission()
        ensureExam(ExamType.CIVIL_SERVANT)
    }

    @After
    fun restoreCivilServant() = ensureExam(ExamType.CIVIL_SERVANT)

    private fun waitForTag(tag: String, timeout: Long = 15_000) {
        compose.waitUntil(timeout) {
            compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForTextContaining(text: String, timeout: Long = 15_000) {
        compose.waitUntil(timeout) {
            compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun backToTabs() {
        repeat(3) {
            if (compose.onAllNodesWithText("今日").fetchSemanticsNodes().isNotEmpty()) return
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
        }
        waitForTextContaining("今日")
    }

    /** 幂等：把当前考试切到目标类型；已经是目标类型时点一下当前项即可关闭面板。 */
    private fun ensureExam(type: ExamType) {
        backToTabs()
        waitForTag("exam-switch")
        compose.onNodeWithTag("exam-switch").performClick()
        waitForTag("exam-option-${type.key}")
        val current = compose.onAllNodes(
            hasTestTag("exam-option-${type.key}") and hasAnyDescendant(hasText("当前")),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().isNotEmpty()
        compose.onNodeWithTag("exam-option-${type.key}").performClick()
        if (!current) {
            // 面板关闭后 DataStore 才落盘；用切换提示确认状态已生效。
            waitForTextContaining("已切换到${type.label}")
        }
    }

    @Test
    fun switchingExamIsolatesCatalogAndRecords() {
        ensureExam(ExamType.CIVIL_SERVANT)

        // 切到四六级：今日页倒计时行、记录数与科目都变成该考试自己的。
        compose.onNodeWithTag("exam-switch").performClick()
        waitForTag("exam-option-CET")
        compose.onNode(
            hasText("0 条 · 0 题") and hasAnyAncestor(hasTestTag("exam-option-CET")),
            useUnmergedTree = true,
        ).assertExists()
        compose.onNodeWithTag("exam-option-CET").performClick()
        waitForTextContaining("四六级 · 未设置考试日期")

        compose.onNodeWithText("开始计时").performClick()
        waitForTextContaining("准备好就开始")
        compose.onNodeWithText("听力").assertExists()
        compose.onNodeWithText("科目").performClick()
        compose.onNode(hasText("阅读") and hasAnyAncestor(isPopup())).assertExists()
        compose.onNode(hasText("言语理解与判断") and hasAnyAncestor(isPopup())).assertDoesNotExist()
        compose.onNode(hasText("听力") and hasAnyAncestor(isPopup())).performClick()
    }
}
