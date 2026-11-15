package com.tiji.studyclock.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/*
 * 四页共用的排版基础件。
 *
 * 这一层的存在理由：v0.11–v0.14 每加一个功能就新加一张「边框卡片」，看板因此攒到 18 个同级盒子，
 * 盒子之间全是 24dp 等距留白——读者看不出哪块重要，就是「乱」。这里把分组方式换成
 * 「章节标题 + 细线 + 更紧的内部间距」，边框只留给需要点击的东西。
 */

/** 章节：朱砂编号 + 墨色标题 + 可选的限定语。分组靠留白与细线，不给每个数据块套边框。 */
@Composable
internal fun ChapterHead(
    mark: String,
    title: String,
    note: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                mark,
                // 两位数字 + 等宽数字：五个章号的宽度一致，标题的左边缘才落在同一条竖线上。
                // 先前用「一 / 二 / 三」当章号，真机上看「一」就是一条朱砂横杠，读不成章序。
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.widthIn(min = 20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
        if (note != null) {
            Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 数据带里的一格：小标题、衬线数字、一行样本说明。 */
internal data class Figure(
    val label: String,
    val value: String,
    val note: String? = null,
    val muted: Boolean = false,
)

/**
 * 数据带：二到四格并排，用竖细线分栏。
 *
 * 比「三行 label + 数字 + 说明」省一半高度，而且三个数字并排时高低一眼可比——
 * 这正是三轨要的回答方式：互不合并，但可以并列看。
 */
@Composable
internal fun FigureStrip(items: List<Figure>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.Top) {
        items.forEachIndexed { index, item ->
            if (index > 0) {
                Spacer(Modifier.width(12.dp))
                Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    item.value,
                    style = if (items.size >= 3) {
                        MaterialTheme.typography.headlineSmall
                    } else {
                        MaterialTheme.typography.headlineMedium
                    },
                    color = if (item.muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                )
                item.note?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 占比条的一段：颜色由调用方给，颜色语义由四轨统一（题墨 / 分赭 / 复苔 / 专朱）。 */
internal data class ShareSegment(val label: String, val fraction: Float, val color: Color)

/**
 * 一条 100% 堆叠条：把「时间花在哪类记录上」压成一行，而不是四段各占三行。
 *
 * 颜色只做视觉分组，朗读时把每一段都念出来——否则读屏用户只剩一个色块。
 */
@Composable
internal fun ShareBar(segments: List<ShareSegment>, modifier: Modifier = Modifier) {
    val description = segments.joinToString("，") { "${it.label} ${(it.fraction * 100).roundToInt()}%" }
    Row(
        modifier.fillMaxWidth().height(8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(1.dp))
            .semantics { contentDescription = description },
    ) {
        segments.filter { it.fraction > 0f }.forEach { segment ->
            Box(
                Modifier.weight(segment.fraction.coerceAtLeast(0.01f)).fillMaxHeight()
                    .background(segment.color),
            )
        }
    }
}

/** 一行一条：标签、比例条、右侧读数。用于常练时段这类「同量纲比大小」。 */
@Composable
internal fun BarRow(
    label: String,
    value: String,
    fraction: Float,
    tone: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.fillMaxWidth().height(5.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.01f, 1f)).fillMaxHeight().background(tone))
        }
    }
}

/** 左标签右读数：把一串独立的小 Text 收成一种行，避免「每句话都占一行」。 */
@Composable
internal fun FactRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.End,
        )
    }
}

/** 跳到别处的入口行：标题 + 一句实情 + 箭头，整行可点，最小 56dp。 */
@Composable
internal fun EntryRow(
    title: String,
    meta: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 折叠说明：把「为什么不合并三条轨」「备份为什么不能防丢机」这类长解释收进一次点击。
 *
 * 这些规则本身要留（它们解释了口径），但六段并列的免责声明会让页面读起来像说明书；
 * 收起来之后它们仍然可查，只是不再抢注意力。
 */
@Composable
internal fun Disclosure(title: String, body: String, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (open) "收起" else "展开",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        AnimatedVisibility(open) {
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
}

/**
 * 等宽分段标签：选中项用朱砂细线压底，而不是胶囊。
 *
 * 记录页把「记录 / 错题复做」提升成这个控件，下方的筛选条件就退成一行，
 * 不再出现两排形状相同的 FilterChip 互相争夺「我在哪一层」。
 */
@Composable
internal fun UnderlineTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            Column(
                Modifier.weight(1f).heightIn(min = 48.dp).clickable { onSelect(index) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    label,
                    style = if (active) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    color = if (active) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                Box(
                    Modifier.fillMaxWidth().height(2.dp)
                        .background(if (active) MaterialTheme.colorScheme.secondary else Color.Transparent),
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
}
