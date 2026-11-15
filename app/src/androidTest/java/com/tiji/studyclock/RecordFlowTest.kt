package com.tiji.studyclock

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import kotlin.random.Random
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun waitForText(text: String, timeout: Long = 15_000) {
        compose.waitUntil(timeout) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label, substring = true))

    private fun fill(label: String, value: String) {
        field(label).performScrollTo().performTextReplacement(value)
    }

    private fun assertField(label: String, value: String) {
        field(label).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))
    }

    private fun choose(label: String, value: String) {
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.onNode(hasText(value) and hasAnyAncestor(isPopup())).performClick()
    }

    private fun openRecord() {
        waitForText("补记一次")
        compose.onNodeWithText("补记一次").performScrollTo().performClick()
    }

    private fun fillCounts(total: Int, wrong: Int) {
        fill("题量", total.toString())
        fill("错题数", wrong.toString())
    }

    /** 随机时长让同一份测试反复运行时仍能唯一定位本次记录。 */
    private fun uniqueDuration(): String {
        val minutes = Random.nextInt(1_000, 1_000_000)
        val seconds = Random.nextInt(60)
        fill("分钟", minutes.toString())
        fill("秒", seconds.toString())
        return "$minutes:${seconds.toString().padStart(2, '0')}"
    }

    private fun saveRecord() {
        compose.onNodeWithText("保存练习").performClick()
        waitForText("练习已保存")
        // 补记/计时保存回今日页，编辑保存回记录页；两者的共同点是底部导航重新出现。
        waitForText("看板")
    }

    private fun scrollTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        // 今日页与记录页各有一个纵向列表；按当前页面选择列表标签，避免多匹配。
        val listTag = if (compose.onAllNodes(hasTestTag("records-list")).fetchSemanticsNodes().isNotEmpty()) {
            "records-list"
        } else {
            "today-list"
        }
        compose.waitUntil(10_000) {
            try {
                compose.onNodeWithTag(listTag).performScrollToNode(matcher)
                compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            } catch (_: AssertionError) {
                false // 导航或数据库 Flow 可能尚未更新列表。
            }
        }
        return compose.onAllNodes(matcher, useUnmergedTree = true).onFirst().performScrollTo()
    }

    // 记录卡的题量、答对数、时长与类别是同级文本节点（布局 Row 不产生语义节点）。
    private fun cardMatcher(total: Int, correct: Int, category: String, duration: String): SemanticsMatcher =
        hasText(category) and
            hasAnySibling(hasText(total.toString())) and
            hasAnySibling(hasText("答对 $correct", substring = true)) and
            hasAnySibling(hasText(duration))

    private fun record(total: Int, correct: Int, category: String, duration: String) =
        scrollTo(cardMatcher(total, correct, category, duration))

    private fun recordAction(total: Int, correct: Int, category: String, duration: String, action: String) {
        // 合并树里按钮自身带文本；用“同一卡片内”约束，避免点到其他记录的按钮。
        compose.onAllNodes(hasText(action) and hasAnySibling(cardMatcher(total, correct, category, duration)))
            .onFirst().performScrollTo().performClick()
    }

    private fun saveSettingsAndRecreate() {
        compose.onNodeWithText("保存设置").performScrollTo().performClick()
        waitForText("设置已保存")
        compose.activityRule.scenario.recreate()
        waitForText("保存设置")
    }

    @Test fun manualRecordWithoutTimeSurvivesActivityRecreation() {
        openRecord()
        choose("科目", "言语理解与判断")
        choose("类别", "主旨概括")
        fillCounts(16, 4)
        fill("分钟", "")
        fill("秒", "")
        saveRecord()
        compose.activityRule.scenario.recreate()
        waitForText("补记一次")
        compose.onNodeWithText("记录").performClick()
        record(16, 12, "主旨概括", "未计时").assertIsDisplayed()
    }

    @Test fun wrongKeywordIsSavedAndShownOnTheRecord() {
        val total = Random.nextInt(300, 900)
        openRecord()
        choose("科目", "资料分析")
        choose("类别", "平均数与倍数")
        fillCounts(total, 2)
        compose.onNodeWithTag("wrong-tag-input").performScrollTo().performTextInput("属性规律")
        compose.onNodeWithText("添加").performScrollTo().performClick()
        fill("分钟", "")
        fill("秒", "")
        saveRecord()
        compose.onNodeWithText("记录").performClick()
        record(total, total - 2, "平均数与倍数", "未计时").assertIsDisplayed()
        compose.onAllNodesWithText("属性规律").onFirst().assertIsDisplayed()
    }

    @Test fun zeroQuestionsAreRejectedWithoutLeavingForm() {
        openRecord()
        fillCounts(0, 0)
        compose.onNodeWithText("保存练习").performClick()
        compose.onNodeWithText("题量必须是大于 0 的整数。").assertIsDisplayed()
        compose.onNodeWithText("保存练习").assertExists()
    }

    @Test fun tooManyWrongAnswersAreRejected() {
        openRecord()
        fillCounts(16, 20)
        compose.onNodeWithText("保存练习").performClick()
        compose.onNodeWithText("错题数需要在 0 与题量之间。").assertIsDisplayed()
    }

    @Test fun changingSubjectWhileEditingKeepsSavedQuestionCounts() {
        openRecord()
        choose("科目", "数量关系")
        choose("类别", "数字推理")
        fillCounts(40, 10)
        val duration = uniqueDuration()
        saveRecord()
        compose.onNodeWithText("记录").performClick()
        recordAction(40, 30, "数字推理", duration, "编辑")
        assertField("题量", "40")
        assertField("错题数", "10")

        choose("科目", "资料分析")
        compose.onNodeWithText("科目").assert(hasText("资料分析"))
        assertField("题量", "40")
        assertField("错题数", "10")
        compose.onNodeWithText("答对 30 / 40 题（单次结果）").performScrollTo().assertIsDisplayed()
        // 上一次保存的 Snackbar 会盖住底部按钮；等它消失再保存，避免点击落在提示上。
        compose.waitUntil(20_000) { compose.onAllNodesWithText("练习已保存").fetchSemanticsNodes().isEmpty() }
        saveRecord()
        record(40, 30, "增长与比重", duration).assertIsDisplayed()
    }

    @Test fun deletedRecordCanBeUndoneImmediatelyAfterActivityRecreation() {
        openRecord()
        choose("科目", "判断推理")
        choose("类别", "图形推理")
        fillCounts(23, 7)
        val duration = uniqueDuration()
        saveRecord()
        compose.onNodeWithText("记录").performClick()
        recordAction(23, 16, "图形推理", duration, "删除")
        // 确认原删除事件已被消费，再立即重建，不依赖未消费事件的偶然重放。
        waitForText("撤销", 2_000)
        compose.activityRule.scenario.recreate()
        waitForText("撤销", 2_000)
        compose.onNodeWithText("记录已移除").assertIsDisplayed()
        compose.onAllNodes(cardMatcher(23, 16, "图形推理", duration)).assertCountEquals(0)
        compose.onNodeWithText("撤销").performClick()
        record(23, 16, "图形推理", duration).assertIsDisplayed()
        recordAction(23, 16, "图形推理", duration, "编辑")
        assertField("题量", "23")
        assertField("错题数", "7")
    }

    @Test fun examDateAndQuestionGoalCanBeSavedThenClearedAcrossRecreation() {
        waitForText("补记一次")
        compose.onNodeWithText("我的").performClick()
        val examName = "回归考试-${System.nanoTime()}"
        val examDate = LocalDate.now().plusDays(30).toString()
        fill("考试名称", examName)
        fill("考试日期（选填）", examDate)
        fill("题量", "37")
        fill("分钟", "")
        saveSettingsAndRecreate()
        assertField("考试日期（选填）", examDate)
        assertField("题量", "37")
        // 同时核对今日页的实际设置，避免只验证 rememberSaveable 恢复的表单草稿。
        compose.onNodeWithText("今日").performClick()
        scrollTo(hasText("$examName · 还有 30 天")).assertIsDisplayed()
        scrollTo(hasText("每日目标 37 题")).assertIsDisplayed()

        compose.onNodeWithText("我的").performClick()
        fill("考试日期（选填）", "")
        fill("题量", "")
        saveSettingsAndRecreate()
        assertField("考试日期（选填）", "")
        assertField("题量", "")
        compose.onNodeWithText("今日").performClick()
        scrollTo(hasText("$examName · 未设置考试日期")).assertIsDisplayed()
        compose.onAllNodesWithText("每日目标 37 题").assertCountEquals(0)
    }

    @Test fun perQuestionAnswersSurviveSavingAndEditing() {
        openRecord()
        choose("科目", "常识判断")
        choose("类别", "政治常识")
        fillCounts(6, 0)
        val duration = uniqueDuration()
        compose.onNodeWithText("逐题回填（可选）").performScrollTo().performClick()
        compose.onNodeWithText("2 对").performScrollTo().performClick()
        compose.onNodeWithText("5 对").performScrollTo().performClick()
        compose.onNodeWithText("3 对").performScrollTo().performClick()
        compose.onNodeWithText("3 错").performClick() // 再点一次恢复为对。
        assertField("错题数", "2")
        saveRecord()
        compose.activityRule.scenario.recreate()
        waitForText("补记一次")
        compose.onNodeWithText("记录").performClick()
        recordAction(6, 4, "政治常识", duration, "编辑")
        assertField("题量", "6")
        assertField("错题数", "2")
        compose.onNodeWithText("关闭回填，保留错题数").performScrollTo().assertIsDisplayed()
        (1..6).forEach { number ->
            val wrong = number == 2 || number == 5
            val chip = compose.onNodeWithText("$number ${if (wrong) "错" else "对"}").performScrollTo()
            if (wrong) chip.assertIsSelected() else chip.assertIsNotSelected()
        }
    }

    @Test fun heatmapCellOpensThatDaysRecords() {
        val today = LocalDate.now()
        openRecord()
        choose("科目", "资料分析")
        choose("类别", "平均数与倍数")
        fillCounts(12, 3)
        val duration = uniqueDuration()
        saveRecord()
        compose.onNodeWithText("看板").performClick()
        waitForText("学习看板")
        scrollToTag("heatmap-day-${today.dayOfMonth}")
        compose.onNodeWithTag("heatmap-day-${today.dayOfMonth}").performClick()
        waitForText("${today.monthValue} 月 ${today.dayOfMonth} 日")
        compose.onAllNodes(cardMatcher(12, 9, "平均数与倍数", duration), useUnmergedTree = true)
            .onFirst().assertExists()
        compose.onNode(hasText("组 ·", substring = true)).assertExists()
    }

    private fun scrollToTag(tag: String) {
        compose.waitUntil(10_000) {
            try {
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))
                compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
            } catch (_: AssertionError) {
                false
            }
        }
        compose.onNodeWithTag(tag).performScrollTo()
    }

    @Test fun settingsOffersRealBackupAndRestoreEntries() {
        waitForText("补记一次")
        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithText("备份全部数据").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("从备份恢复").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("备份与恢复").assertCountEquals(0)
    }

    @Test fun recordFooterReflectsPagingAndGrouping() {
        compose.onNodeWithText("记录").performClick()
        waitForText("练习记录")
        scrollTo(hasText("天", substring = true) and footerMatcher()).assertIsDisplayed()
        compose.onNodeWithTag("records-list").performScrollToIndex(0)
        compose.onNodeWithTag("records-sort").performClick()
        compose.onNodeWithText("题量从多到少").performClick()
        waitForText("题量从多到少 ▾")
        scrollTo(hasText("条", substring = true) and footerMatcher()).assertIsDisplayed()
    }

    private fun footerMatcher() =
        hasText("已显示全部", substring = true) or hasText("加载更早", substring = true)
}
