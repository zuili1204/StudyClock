package com.tiji.studyclock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tiji.studyclock.data.repo.BackfillPreview
import com.tiji.studyclock.domain.model.RecordMode
import com.tiji.studyclock.domain.model.ScoreRules
import com.tiji.studyclock.domain.model.SessionKind
import com.tiji.studyclock.domain.model.TagRules
import com.tiji.studyclock.domain.model.WrongBatch
import com.tiji.studyclock.domain.stats.BatchStatus
import com.tiji.studyclock.domain.stats.ConfidenceLevel
import com.tiji.studyclock.domain.stats.Metric
import com.tiji.studyclock.domain.stats.ModeBucket
import com.tiji.studyclock.domain.stats.RedoLedger
import com.tiji.studyclock.domain.stats.ScoreEngine
import com.tiji.studyclock.domain.stats.TrackComparison
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** 错题袋列表的筛选；筛选只影响显示，不改变任何统计口径（§5.7）。 */
enum class BagFilter(val label: String) {
    PENDING("待消化"), DIGESTED("已消化"), ARCHIVED("已归档"), ALL("全部"),
}

private val CARD_SHAPE = RoundedCornerShape(2.dp)

@Composable
private fun v5ChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = Color.Transparent,
    labelColor = MaterialTheme.colorScheme.onBackground,
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
)

@Composable
private fun V5Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CARD_SHAPE)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

private fun percent(value: Double): String = String.format(Locale.CHINA, "%.1f%%", value * 100)

private fun durationText(seconds: Long): String = when {
    seconds < 60 -> "$seconds 秒"
    seconds < 3600 -> "${seconds / 60} 分钟"
    else -> "${seconds / 3600} 小时 ${seconds % 3600 / 60} 分"
}


/** 结算 / 补记 / 编辑共用的模式选择；切换前二次确认，确认后由调用方清空互斥字段。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordModeSelector(
    selected: RecordMode,
    onSelect: (RecordMode) -> Unit,
    hasIncompatibleInput: Boolean = false,
    /** 已填过题量：切到「专注」会把它清掉，所以同样要二次确认（D27）。 */
    hasPracticeInput: Boolean = false,
    enabled: Boolean = true,
) {
    var pending by remember { mutableStateOf<RecordMode?>(null) }
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("切换记录方式？") },
            text = { Text("切换后会清空与新方式不兼容的输入（题量、错题数、计分项目与得分）。已保存的历史记录不会改动。") },
            confirmButton = {
                TextButton(onClick = { onSelect(pending!!); pending = null }) { Text("切换并清空") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } },
        )
    }
    fun needsConfirm(next: RecordMode): Boolean =
        selected != next && (hasIncompatibleInput || (next == RecordMode.FOCUS && hasPracticeInput))

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("记录方式", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selected == RecordMode.COUNT,
                enabled = enabled,
                onClick = { if (needsConfirm(RecordMode.COUNT)) pending = RecordMode.COUNT else onSelect(RecordMode.COUNT) },
                label = { Text("正确率 · 客观题", style = MaterialTheme.typography.bodySmall) },
                shape = CARD_SHAPE,
                colors = v5ChipColors(),
                modifier = Modifier.heightIn(min = 44.dp),
            )
            FilterChip(
                selected = selected == RecordMode.SCORE,
                enabled = enabled,
                onClick = { if (needsConfirm(RecordMode.SCORE)) pending = RecordMode.SCORE else onSelect(RecordMode.SCORE) },
                label = { Text("得分 · 主观题", style = MaterialTheme.typography.bodySmall) },
                shape = CARD_SHAPE,
                colors = v5ChipColors(),
                modifier = Modifier.heightIn(min = 44.dp),
            )
            FilterChip(
                selected = selected == RecordMode.FOCUS,
                enabled = enabled,
                onClick = { if (needsConfirm(RecordMode.FOCUS)) pending = RecordMode.FOCUS else onSelect(RecordMode.FOCUS) },
                label = { Text("专注 · 只记时长", style = MaterialTheme.typography.bodySmall) },
                shape = CARD_SHAPE,
                colors = v5ChipColors(),
                modifier = Modifier.heightIn(min = 44.dp),
            )
        }
        Text(
            "正确率记客观题，得分记作文/申论，专注只记时长。三者不会合并成一个综合分。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 分数输入只允许数字和 1 位小数；具体合法性交给 ScoreRules。 */
fun sanitizeScore(raw: String): String = buildString {
    var dotSeen = false
    raw.forEach { char ->
        when {
            char.isDigit() && length < 5 -> append(char)
            char == '.' && !dotSeen && length < 5 -> { dotSeen = true; append(char) }
        }
    }
}.let { text ->
    // 「.5」补成「0.5」，否则 ScoreRules 的正则会拒绝一个用户看起来完全合法的输入。
    val normalized = if (text.startsWith(".")) "0$text" else text
    val parts = normalized.split('.')
    if (parts.size > 1) parts[0] + "." + parts[1].take(1) else normalized
}

/** 三个表单共用的计分校验；返回 null 表示通过。 */
fun scoreInputError(label: String, score: String, maxScore: String): String? {
    val scoreTenths = ScoreRules.parseTenths(score)
        ?: return "得分需要填写，最多 1 位小数。"
    val maxTenths = ScoreRules.parseTenths(maxScore)
        ?: return "满分需要填写，最多 1 位小数。"
    return ScoreRules.error(scoreTenths, maxTenths, label.takeIf { it.isNotBlank() })
}

/** 计分项目 / 得分 / 满分 + 常用满分档 + 得分率预览。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScoreFields(
    label: String,
    onLabel: (String) -> Unit,
    score: String,
    onScore: (String) -> Unit,
    maxScore: String,
    onMax: (String) -> Unit,
    rememberedMaxTenths: Int?,
    enabled: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = label,
            onValueChange = { onLabel(it.take(ScoreRules.MAX_LABEL_LENGTH)) },
            enabled = enabled,
            label = { Text("计分项目（选填）") },
            placeholder = { Text("大作文 / 小题 1 / 雅思写作") },
            singleLine = true,
            supportingText = { Text("最多 ${ScoreRules.MAX_LABEL_LENGTH} 个字，用于区分不同的计分项") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = score,
                onValueChange = { onScore(sanitizeScore(it)) },
                enabled = enabled,
                label = { Text("得分") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            )
            OutlinedTextField(
                value = maxScore,
                onValueChange = { onMax(sanitizeScore(it)) },
                enabled = enabled,
                label = { Text("满分") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            )
        }
        val quick = buildList {
            rememberedMaxTenths?.let { add(it to "上次 ${ScoreRules.format(it)}") }
            ScoreRules.COMMON_MAX_SCORES.forEach { add(it to ScoreRules.format(it)) }
        }.distinctBy { it.first }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("常用满分", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            quick.forEach { (tenths, text) ->
                FilterChip(
                    selected = maxScore == ScoreRules.format(tenths),
                    enabled = enabled,
                    onClick = { onMax(ScoreRules.format(tenths)) },
                    label = { Text(text, style = MaterialTheme.typography.bodySmall) },
                    shape = CARD_SHAPE,
                    colors = v5ChipColors(),
                    modifier = Modifier.heightIn(min = 44.dp),
                )
            }
        }
        val rate = ScoreRules.rate(ScoreRules.parseTenths(score), ScoreRules.parseTenths(maxScore))
        Text(
            rate?.let { "得分率 ${percent(it)}（本次结果，不进主正确率）" } ?: "填好得分与满分后显示得分率",
            style = MaterialTheme.typography.bodySmall,
            color = if (rate != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun batchStatusLabel(status: BatchStatus): String = when (status) {
    BatchStatus.PENDING -> "待消化"
    BatchStatus.DIGESTED -> "已消化"
    BatchStatus.ARCHIVED -> "已归档"
}

private fun attemptDate(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

/** 记录页「错题袋」分段的内容；筛选只影响显示。 */
fun LazyListScope.wrongBatchesSection(
    state: AppState,
    model: MainViewModel,
    filter: BagFilter,
    onFilter: (BagFilter) -> Unit,
    openBag: (Long) -> Unit,
) {
    val ledgers = RedoLedger.ledgers(state.wrongBatches, state.reviewAttempts, state.today)
        .sortedWith(compareByDescending<com.tiji.studyclock.domain.stats.BatchLedger> { it.status == BatchStatus.PENDING }
            .thenByDescending { it.waitingDays ?: 0 }
            .thenByDescending { it.batch.createdAt })
    val shown = ledgers.filter { row ->
        when (filter) {
            BagFilter.ALL -> true
            BagFilter.PENDING -> row.status == BatchStatus.PENDING
            BagFilter.DIGESTED -> row.status == BatchStatus.DIGESTED
            BagFilter.ARCHIVED -> row.status == BatchStatus.ARCHIVED
        }
    }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BagFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { onFilter(option) },
                        label = { Text(option.label, style = MaterialTheme.typography.bodySmall) },
                        shape = CARD_SHAPE,
                        colors = v5ChipColors(),
                        modifier = Modifier.heightIn(min = 44.dp),
                    )
                }
            }
            Text(
                "共 ${ledgers.size} 个错题袋 · 待复做 ${ledgers.filter { it.status == BatchStatus.PENDING }.sumOf { it.remaining }} 题",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "一次普通练习有错题就自动生成一个袋，代表「这 N 道错题」；剩余题数、轮次与消化率全部由复做记录派生。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = model::showBackfillPreview,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = CARD_SHAPE,
            ) { Text("从历史记录导入错题袋") }
            Text(
                "历史错题不会自动回填；只有在这里确认后才会建袋，且回填袋不计入逾期提醒。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
    if (shown.isEmpty()) {
        item {
            Text(
                if (ledgers.isEmpty()) "还没有错题袋。做完一组客观题并留下错题，就会自动生成一个。"
                else "这个筛选下没有错题袋。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            )
        }
    } else {
        items(shown, key = { it.batch.id }) { row ->
            WrongBatchRow(row.batch, row.remaining, row.status, row.rounds, state) { openBag(row.batch.id) }
        }
    }
}

@Composable
private fun WrongBatchRow(
    batch: WrongBatch,
    remaining: Int,
    status: BatchStatus,
    rounds: Int,
    state: AppState,
    open: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 76.dp)
                .clickableV5(open)
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    state.categories.find { it.id == batch.categoryId }?.name ?: "已归档类别",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${state.subjects.find { it.id == batch.subjectId }?.name.orEmpty()} · ${batch.originDate} · ${batch.wrongCount} 道错题",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${batchStatusLabel(status)} · 还剩 $remaining 题 · 已复做 $rounds 轮${if (batch.backfilled) " · 历史导入" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val tags = TagRules.parse(batch.wrongTags)
                if (tags.isNotEmpty()) {
                    Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
            Text("查看 ›", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

private fun Modifier.clickableV5(onClick: () -> Unit): Modifier = this.then(
    Modifier.clickable(onClick = onClick),
)

/** D18 历史回填确认框：先预览、再写入，绝不自动回填。 */
@Composable
fun BackfillPreviewDialog(model: MainViewModel) {
    val preview by model.backfillPreview.collectAsStateWithLifecycle()
    val data = preview ?: return
    AlertDialog(
        onDismissRequest = model::dismissBackfillPreview,
        title = { Text("从历史记录导入错题袋") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (data.batchCount == 0) {
                    Text("没有需要导入的历史错题：所有带错题的普通记录都已经有错题袋了。")
                } else {
                    Text("将把 ${data.batchCount} 条历史练习转成错题袋，共 ${data.wrongQuestions} 道错题。")
                    Text("日期范围：${data.earliest ?: "—"} 至 ${data.latest ?: "—"}。", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "这些袋会标注为「历史导入」，不计入逾期提醒；不会改写任何历史记录，也不会自动生成复做轮次。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = if (data.batchCount == 0) model::dismissBackfillPreview else model::applyBackfill,
            ) { Text(if (data.batchCount == 0) "知道了" else "确认导入") }
        },
        dismissButton = {
            if (data.batchCount > 0) TextButton(onClick = model::dismissBackfillPreview) { Text("取消") }
        },
    )
}

/** 错题袋详情：剩余、轮次时间线、备注、归档与「开始复做」。 */
@Composable
fun WrongBatchDetailScreen(
    state: AppState,
    batchId: Long,
    model: MainViewModel,
    openTimer: () -> Unit,
) {
    val batch = state.wrongBatches.find { it.id == batchId }
    if (batch == null) {
        Text("这个错题袋已不存在。", modifier = Modifier.padding(24.dp))
        return
    }
    val attempts = state.reviewAttempts.filter { it.batchId == batchId }
    val ledger = RedoLedger.ledger(batch, attempts, state.today)
    var noteDraft by rememberSaveable(batchId) { mutableStateOf(batch.note.orEmpty()) }
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    state.categories.find { it.id == batch.categoryId }?.name ?: "已归档类别",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "${state.subjects.find { it.id == batch.subjectId }?.name.orEmpty()} · 来源日期 ${batch.originDate}${if (batch.backfilled) " · 历史导入" else ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${ledger.remaining}", style = MaterialTheme.typography.displayLarge)
                    Text(" / ${batch.wrongCount} 题待复做", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                }
                Text(
                    "${batchStatusLabel(ledger.status)} · 已复做 ${ledger.rounds} 轮 · 消化率 ${percent(ledger.digestionRate)}" +
                        (ledger.waitingDays?.let { " · 已等待 $it 天" }.orEmpty()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val tags = TagRules.parse(batch.wrongTags)
                if (tags.isNotEmpty()) {
                    Text("关键词：${tags.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
                state.prescriptions.find { it.batchId == batchId }?.let { linked ->
                    Text(
                        "关联处方：${linked.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Button(
                onClick = { model.startRedo(batchId, openTimer) },
                enabled = ledger.status == BatchStatus.PENDING && !batch.archived,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                shape = CARD_SHAPE,
            ) { Text(if (ledger.status == BatchStatus.PENDING) "开始复做" else "这个袋已经消化完了") }
            Text(
                "复做会带出本袋的科目与类别；结束后填写本轮重做题数与通过数。复做记录计入题量与时长，但不计入主正确率。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            V5Card("备注") {
                OutlinedTextField(
                    value = noteDraft,
                    onValueChange = { noteDraft = it.take(ScoreRules.MAX_NOTE_LENGTH) },
                    label = { Text("给这个袋留一句提示（选填）") },
                    supportingText = { Text("最多 ${ScoreRules.MAX_NOTE_LENGTH} 个字") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { model.setBatchNote(batchId, noteDraft) }) { Text("保存备注") }
                    TextButton(onClick = { noteDraft = ""; model.setBatchNote(batchId, null) }) { Text("清空") }
                }
            }
        }
        item {
            V5Card("复做时间线 · ${ledger.rounds} 轮") {
                if (attempts.isEmpty()) {
                    Text("还没有复做过。第一轮之后，这里会逐轮显示通过数与日期。", style = MaterialTheme.typography.bodySmall)
                } else {
                    attempts.sortedBy { it.createdAt }.forEachIndexed { index, attempt ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("第 ${index + 1} 轮 · ${attemptDate(attempt.createdAt)}", style = MaterialTheme.typography.bodyMedium)
                                val session = state.sessions.find { it.id == attempt.sessionId }
                                Text(
                                    "重做 ${attempt.attemptedCount} 题 · 过 ${attempt.passedCount} 题" +
                                        (session?.wrongTags?.let { " · ${TagRules.parse(it).joinToString("、")}" }.orEmpty()),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                if (attempt.attemptedCount > 0) percent(attempt.passedCount.toDouble() / attempt.attemptedCount) else "—",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                ledger.firstPassRate?.let {
                    Text("首轮通过率 ${percent(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { model.setBatchArchived(batchId, !batch.archived) }) {
                    Text(if (batch.archived) "取消归档" else "归档这个袋")
                }
            }
            Text(
                "归档后不再计入待复做，也不会自动解除已关联处方的指向以外的任何数据；取消归档不会自动重新挂接处方。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 记录卡第 3 行的文案：按模式/性质分轨显示，不合并（§5.7）。 */
fun sessionResultLine(
    mode: RecordMode,
    kind: SessionKind,
    total: Int,
    correct: Int,
    scoreTenths: Int?,
    maxTenths: Int?,
    durationSec: Int? = null,
): String = when {
    mode == RecordMode.FOCUS -> "专注 ${durationText((durationSec ?: 0).toLong())}"
    kind == SessionKind.REDO -> "复做 $total 题 · 过 $correct"
    mode == RecordMode.SCORE -> {
        val rate = ScoreRules.rate(scoreTenths, maxTenths)
        val score = scoreTenths?.let(ScoreRules::format) ?: "—"
        val max = maxTenths?.let(ScoreRules::format) ?: "—"
        if (rate != null) "得分 $score / $max（${percent(rate)}）" else "得分 $score / $max"
    }
    else -> "答对 $correct · ${if (total > 0) percent(correct.toDouble() / total) else "—"}"
}
