package com.tiji.studyclock

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimerFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun allowNotifications() = grantNotificationPermission()

    private fun waitForText(text: String, timeout: Long = 15_000) {
        compose.waitUntil(timeout) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun fill(label: String, value: String) {
        compose.onNode(hasSetTextAction() and hasText(label)).performScrollTo().performTextReplacement(value)
    }

    private fun choose(label: String, value: String) {
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.onNode(hasText(value) and hasAnyAncestor(isPopup())).performClick()
    }

    private fun scrollTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitUntil(10_000) {
            try {
                compose.onNodeWithTag("records-list").performScrollToNode(matcher)
                compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            } catch (_: AssertionError) {
                false
            }
        }
        return compose.onAllNodes(matcher, useUnmergedTree = true).onFirst().performScrollTo()
    }

    private fun recordCard(total: Int, correct: Int, category: String) =
        scrollTo(
            hasText(category) and
                hasAnySibling(hasText(total.toString())) and
                hasAnySibling(hasText("答对 $correct", substring = true)),
        )

    /** Tests share one app data store; a failed run must not leave a timer running for the next test. */
    private fun clearTimerIfRunning() {
        waitForText("开始计时")
        if (compose.onAllNodesWithText("计时进行中").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("回到计时").performScrollTo().performClick()
            compose.onNodeWithText("放弃本次计时").performScrollTo().performClick()
            compose.onNodeWithText("放弃").performClick()
            waitForText("准备好就开始")
            compose.onNodeWithContentDescription("返回").performClick()
            waitForText("开始计时")
        }
        // 自动结算后结果会持久化；上一轮失败留下的“待填写”也要清掉，避免遮住设置页。
        if (compose.onAllNodesWithText("计时已结束").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("开始计时").performScrollTo().performClick()
            waitForText("本次计时已完成")
            compose.onNodeWithText("重新计时").performClick()
            compose.onNodeWithText("放弃").performClick()
            waitForText("准备好就开始")
            compose.onNodeWithContentDescription("返回").performClick()
            waitForText("开始计时")
        }
    }

    @Test fun timerCapturesDateAndDurationAutomatically() {
        clearTimerIfRunning()
        compose.onNodeWithText("开始计时").performScrollTo().performClick()
        waitForText("准备好就开始")
        choose("科目", "判断推理")
        choose("类别", "图形推理")
        compose.onNodeWithText("今天 ·", substring = true).assertExists()
        compose.onNodeWithText("开始计时").performScrollTo().performClick()
        waitForText("结束计时")
        compose.onNodeWithText("结束计时").performScrollTo().performClick()
        waitForText("本次计时已完成")
        compose.onNodeWithText("图形推理", substring = true).assertExists()
        fill("题量", "37")
        fill("错题数", "5")
        compose.onNodeWithText("答对 32 / 37 题（单次结果）").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("保存练习").performClick()
        waitForText("练习已保存")
        waitForText("补记一次")
        compose.onNodeWithText("记录").performClick()
        recordCard(37, 32, "图形推理").assertIsDisplayed()
    }

    @Test fun runningTimerSurvivesLeavingAndActivityRecreation() {
        clearTimerIfRunning()
        compose.onNodeWithText("开始计时").performScrollTo().performClick()
        waitForText("准备好就开始")
        choose("科目", "资料分析")
        choose("类别", "增长与比重")
        compose.onNodeWithText("开始计时").performScrollTo().performClick()
        waitForText("结束计时")
        compose.onNodeWithContentDescription("返回").performClick()
        waitForText("计时进行中")
        compose.activityRule.scenario.recreate()
        waitForText("计时进行中")
        compose.onNodeWithText("回到计时").performScrollTo().performClick()
        waitForText("结束计时")
        compose.onNodeWithText("放弃本次计时").performScrollTo().performClick()
        waitForText("放弃本次计时？")
        compose.onNodeWithText("放弃").performClick()
        waitForText("准备好就开始")
        compose.onNodeWithContentDescription("返回").performClick()
        waitForText("开始计时")
    }
}
