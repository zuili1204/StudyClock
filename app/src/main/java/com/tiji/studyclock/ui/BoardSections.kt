package com.tiji.studyclock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.tiji.studyclock.domain.diagnose.Insight
import com.tiji.studyclock.domain.model.ScoreRules
import com.tiji.studyclock.domain.stats.DayTrack
import com.tiji.studyclock.domain.stats.Metric
import com.tiji.studyclock.domain.stats.ModeBucket
import com.tiji.studyclock.domain.stats.ModeStructure
import com.tiji.studyclock.domain.stats.ScoreEngine
import com.tiji.studyclock.domain.stats.TrackComparison
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * 看板的五章内容。
 *
 * 改造前的看板是 18 个同构卡片平铺：三轨、结构、专注、对照、观察、发现、处方、覆盖、
 * 两个指标块、涨跌、词云、时段、类别、三段免责声明——每加一个功能就在末尾追加一张卡，
 * 于是「哪块最重要」这个信息彻底消失。这里改成五章：节律、表现、投入、值得注意、细节。
 * 每章只回答一个问题，章内靠细线与 12dp 间距成组，章间留 28dp。
 */

/** 二 · 表现：三条轨并排，不合并、不加权。 */
@Composable
internal fun PerformanceChapter(state: AppState) {
    val objective = state.stats?.accuracy
    val scored = state.scoreStats?.rate
    val redo = state.redo?.firstPassRate
    val gap = ScoreEngine.targetGap(
        state.exam.targetScore?.toDouble(),
        state.exam.targetMaxScoreTenths,
        state.sessions,
    )
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ChapterHead("02", "表现", "客观题、主观题、复做各算各的；不合成一个综合分") {
            Text(
                // 这里的三条轨取的是全期样本（数字下方的「N 题」就是全期样本量），
                // 先前写「最近 30 天」与口径不符——本月的事实由上面月历给，不在这里。
                "全部记录",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FigureStrip(
            listOf(
                rateFigure("正确率", objective),
                rateFigure("得分率", scored),
                rateFigure("复做通过", redo),
            ),
        )
        if (gap != null) {
            val gapText = String.format(Locale.CHINA, "%.1f", abs(gap))
            val maxText = state.exam.targetMaxScoreTenths?.let { ScoreRules.format(it) }
            Text(
                buildString {
                    append("目标分 ${state.exam.targetScore}")
                    if (maxText != null) append("（满分 $maxText）")
                    append("：")
                    append(if (gap > 0) "最近同口径均值还差 $gapText 分" else "已超出 $gapText 分")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun rateFigure(label: String, metric: Metric<Double>?): Figure {
    val rate = metric?.value
    val questions = metric?.questionCount ?: 0
    return Figure(
        label = label,
        value = rate?.let { String.format(Locale.CHINA, "%.1f%%", it * 100) } ?: "—",
        note = if (rate == null) "样本不足" else "$questions 题",
        muted = rate == null,
    )
}

/** 三 · 投入：一条 100% 堆叠条回答「时间花在哪类记录上」，四轨配色与月历同源。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InvestmentChapter(state: AppState) {
    val structure: ModeStructure? = state.modeStructure
    val focus = state.focusStats
    val empty = structure == null || structure.totalSessions == 0
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ChapterHead("03", "投入", "四类记录各自计时；时长只说明花在哪，不说明学得怎样") {
            if (!empty) {
                Text(
                    "全部记录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (empty || structure == null) {
            Text(
                "记下一次练习后，这里会显示四类记录各占多少时间。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        val segments = structure.shares
            .filter { it.durationShare > 0.0 }
            .map { ShareSegment(bucketLabel(it.bucket), it.durationShare.toFloat(), traceColor(bucketTrack(it.bucket))) }
        ShareBar(segments)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            structure.shares.forEach { share ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(7.dp).background(traceColor(bucketTrack(share.bucket)), RoundedCornerShape(50)))
                    Text(
                        "${bucketName(share.bucket)} ${(share.durationShare * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            buildList {
                add("共 ${structure.totalSessions} 次")
                add("${structure.totalQuestions} 题")
                if (structure.totalSeconds > 0) add(compactDuration(structure.totalSeconds))
                state.stats?.secondsPerQuestion?.value?.let {
                    add("每题 ${String.format(Locale.CHINA, "%.0f", it)} 秒")
                }
            }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (focus != null && focus.sessionCount > 0) {
            Text(
                buildList {
                    add("专注 ${focus.sessionCount} 次")
                    focus.averageSeconds?.let { add("平均单次 ${compactDuration(it)}") }
                    focus.shareOfStudyTime?.let { add("占学习时长 ${(it * 100).roundToInt()}%") }
                    if (focus.longestStreakDays > 1) add("最长连续 ${focus.longestStreakDays} 天")
                }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 图例用全名：「正确率 62%」会被读成正确率本身，必须带上「记录」二字。 */
private fun bucketName(bucket: ModeBucket): String = when (bucket) {
    ModeBucket.OBJECTIVE -> "正确率记录"
    ModeBucket.SCORED -> "得分记录"
    ModeBucket.REDO -> "错题复做"
    ModeBucket.FOCUS -> "专注"
}

private fun bucketLabel(bucket: ModeBucket): String = when (bucket) {
    ModeBucket.OBJECTIVE -> "正确率"
    ModeBucket.SCORED -> "得分"
    ModeBucket.REDO -> "复做"
    ModeBucket.FOCUS -> "专注"
}

private fun bucketTrack(bucket: ModeBucket): DayTrack = when (bucket) {
    ModeBucket.OBJECTIVE -> DayTrack.OBJECTIVE
    ModeBucket.SCORED -> DayTrack.SCORED
    ModeBucket.REDO -> DayTrack.REDO
    ModeBucket.FOCUS -> DayTrack.FOCUS
}

/**
 * 四 · 值得注意：当前观察 + 其他发现 + 今日处方。
 *
 * 三者原来分散在看板中段的三张卡里，彼此都在说「接下来该做什么」，所以合为一章。
 */
@Composable
internal fun AttentionChapter(
    state: AppState,
    openBags: () -> Unit,
    openPrescription: (Long) -> Unit,
    generatePrescriptions: () -> Unit,
) {
    var evidence by rememberSaveable { mutableStateOf(false) }
    val finding = state.findings.firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ChapterHead("04", "值得注意", "只写可核实的事实，不推断原因")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "当前观察",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                finding?.title ?: "先记录一次，再认识自己的节奏。",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                finding?.action ?: "每次练习只需留下题量与错题数。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (finding != null) {
                Text(
                    if (evidence) "收起依据" else "看依据（${finding.evidenceIds.size} 条记录）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.heightIn(min = 32.dp).clickable { evidence = !evidence }.padding(top = 6.dp),
                )
                if (evidence) {
                    Text(
                        finding.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.sessions.filter { it.id in finding.evidenceIds }.forEach { session ->
                        Text(
                            "${sessionDate(session, state.settings.dateAttribution)} · " +
                                "${state.categories.find { it.id == session.categoryId }?.name.orEmpty()} · " +
                                "${session.correctCount}/${session.totalCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (state.insights.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
            Text(
                "其他发现",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.insights.take(3).forEach { insight -> InsightRow(insight) }
        }
        PrescriptionSection(state, openPrescription, generatePrescriptions)
        if ((state.redo?.totalBatches ?: 0) > 0) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
            EntryRow(
                title = "错题闭环",
                meta = "${state.redo?.pendingQuestions ?: 0} 题待复做 · " +
                    "${state.redo?.digestedBatches ?: 0}/${state.redo?.totalBatches ?: 0} 袋已消化",
                onClick = openBags,
            )
        }
    }
}

@Composable
private fun InsightRow(insight: Insight) {
    val tone: Color = when (insight.tone) {
        Insight.Tone.ATTENTION -> MaterialTheme.colorScheme.secondary
        Insight.Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        Insight.Tone.POSITIVE -> MaterialTheme.colorScheme.primary
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.padding(top = 7.dp).size(5.dp).background(tone, RoundedCornerShape(50)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(insight.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
            Text(
                insight.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 五 · 细节：涨跌、类别三轨对照、常练时段、关键词、覆盖入口。 */
@Composable
internal fun DetailChapter(state: AppState, openCoverage: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ChapterHead("05", "细节", "按类别、时段、关键词拆开；都只描述事实")
        MoverLine(state)
        TrackTable(state)
        HourBlock(state)
        TagCloudCard(state)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
        EntryRow(
            title = "覆盖地图",
            meta = rememberCoverage(state).gapSummary(),
            onClick = openCoverage,
        )
    }
}

/** 类别 × 三轨：同一行里就能看出「客观题稳、主观题偏低」，不合并、不排序猜测。 */
@Composable
private fun TrackTable(state: AppState) {
    val rows = state.trackComparisons
    if (rows.isEmpty()) {
        Text(
            "记录后，这里按类别列出三条轨；任一轨样本不足就留「—」，不补齐、不平均。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val questions = state.stats?.categoryStats.orEmpty().associate { it.categoryId to it.accuracy.questionCount }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shown = if (expanded) rows else rows.take(6)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            TableHead("类别", 1.6f, TextAlign.Start)
            TableHead("题量", 0.8f)
            TableHead("正确率", 1.1f)
            TableHead("得分率", 1.1f)
            TableHead("复做", 1.0f)
        }
        shown.forEach { row -> TrackTableRow(row, questions[row.categoryId] ?: 0) }
        if (rows.size > shown.size) {
            Text(
                "展开全部 ${rows.size} 个类别",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { expanded = true }.padding(top = 10.dp),
            )
        } else if (expanded) {
            Text(
                "收起",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { expanded = false }.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TableHead(label: String, weight: Float, align: TextAlign = TextAlign.End) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
        modifier = Modifier.weight(weight),
    )
}

@Composable
private fun TrackTableRow(row: TrackComparison, questions: Int) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1.6f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                row.categoryName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.focusSeconds > 0) {
                Text(
                    "专注 ${compactDuration(row.focusSeconds)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        TableCell(if (questions > 0) "$questions" else "—", 0.8f)
        TableCell(rateCell(row.objectiveAccuracy), 1.1f)
        TableCell(rateCell(row.scoreRate), 1.1f)
        TableCell(rateCell(row.redoPassRate), 1.0f)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TableCell(text: String, weight: Float) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.weight(weight),
    )
}

private fun rateCell(rate: Double?): String =
    rate?.let { String.format(Locale.CHINA, "%.0f%%", it * 100) } ?: "—"

/** 常练时段：前三名的时段与题量，黄金时段单独一句。 */
@Composable
private fun HourBlock(state: AppState) {
    val distribution = state.stats?.timeDistribution?.value.orEmpty()
    val hourly = distribution.groupBy { it.hour }
        .map { (hour, slots) -> Triple(hour, slots.sumOf { it.questionCount }, slots.sumOf { it.sessionCount }) }
        .sortedWith(compareByDescending<Triple<Int, Int, Int>> { it.second }.thenBy { it.first })
        .take(3)
    val golden = state.stats?.goldenHour
    val hourStats = state.stats?.hourStats.orEmpty()
    val goldenAccuracy = golden?.value?.let { hour -> hourStats.firstOrNull { it.hour == hour }?.accuracy }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("常练时段", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (hourly.isEmpty()) {
            Text(
                "需要带真实起止时间的计时记录：补记只有日期，不会伪造时刻。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val max = hourly.maxOf { it.second }.coerceAtLeast(1)
            hourly.forEach { (hour, questions, sessions) ->
                BarRow(
                    label = String.format(Locale.CHINA, "%02d:00 – %02d:00", hour, hour + 1),
                    value = "$questions 题 · $sessions 次",
                    fraction = questions / max.toFloat(),
                )
            }
        }
        Text(
            when {
                golden?.value == null -> "黄金时段：同一时段累计 30 题、3 次后才会给出。"
                goldenAccuracy?.value == null -> "黄金时段：${golden.value}:00 前后。"
                else -> String.format(
                    Locale.CHINA,
                    "黄金时段：%d:00 前后，正确率 %.1f%%（%d 题 / %d 次，%s）",
                    golden.value, goldenAccuracy.value * 100,
                    goldenAccuracy.questionCount, goldenAccuracy.sessionCount, confidenceLabel(goldenAccuracy.level),
                )
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class Mover(val name: String, val delta: Double)

/** 最近 7 天 vs 前 7 天：只列真正变了的类别，两边各满 8 题才算。 */
@Composable
private fun MoverLine(state: AppState) {
    val movers = remember(state.sessions, state.today) {
        val attribution = state.settings.dateAttribution
        val today = state.today
        val recentFrom = today.minusDays(6)
        val previousFrom = today.minusDays(13)
        val recent = mutableMapOf<Long, IntArray>()
        val previous = mutableMapOf<Long, IntArray>()
        state.sessions.forEach { session ->
            val date: LocalDate = sessionDate(session, attribution)
            val bucket = when {
                !date.isBefore(recentFrom) -> recent
                !date.isBefore(previousFrom) -> previous
                else -> null
            } ?: return@forEach
            val slot = bucket.getOrPut(session.categoryId) { IntArray(2) }
            slot[0] += session.totalCount
            slot[1] += session.correctCount
        }
        recent.mapNotNull { (categoryId, now) ->
            val before = previous[categoryId] ?: return@mapNotNull null
            if (now[0] < 8 || before[0] < 8) return@mapNotNull null
            Mover(
                name = state.categories.find { it.id == categoryId }?.name ?: "已归档类别",
                delta = now[1].toDouble() / now[0] - before[1].toDouble() / before[0],
            )
        }
    }
    val up = movers.filter { it.delta > 0.02 }.sortedByDescending { it.delta }.take(2)
    val down = movers.filter { it.delta < -0.02 }.sortedBy { it.delta }.take(2)
    if (up.isEmpty() && down.isEmpty()) return
    val ink = MaterialTheme.colorScheme.primary
    val warn = MaterialTheme.colorScheme.secondary
    Text(
        buildAnnotatedString {
            append("最近 7 天 vs 前 7 天  ")
            (up + down).forEachIndexed { index, mover ->
                if (index > 0) append(" · ")
                append(mover.name)
                append(" ")
                withStyle(SpanStyle(color = if (mover.delta > 0) ink else warn)) {
                    append(String.format(Locale.CHINA, "%+.1f pt", mover.delta * 100))
                }
            }
        },
        style = MaterialTheme.typography.bodySmall,
    )
}
