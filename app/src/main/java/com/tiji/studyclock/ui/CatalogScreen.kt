package com.tiji.studyclock.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tiji.studyclock.domain.model.CatalogRules
import com.tiji.studyclock.domain.model.Category
import com.tiji.studyclock.domain.model.NameRules
import com.tiji.studyclock.domain.model.Subject

/**
 * v0.12 分类管理（H2-6）。
 *
 * 只做四件事：改名、上移/下移、归档/恢复、删除。
 * 预置科目与 43 条基线只允许排序与归档（D34）；归档不改统计与导出（D35）。
 * 所有判据都来自 [CatalogRules]，界面只负责把「为什么不能点」说清楚。
 */
@Composable
fun CatalogScreen(state: AppState, busy: Boolean, model: MainViewModel) {
    val subjects = remember(state.subjects) { state.subjects.sortedWith(compareBy({ it.sortOrder }, { it.id })) }
    val activeSubjects = remember(subjects) { subjects.filterNot { it.archived } }
    val archivedSubjects = remember(subjects) { subjects.filter { it.archived } }
    val categoriesBySubject = remember(state.categories) { state.categories.groupBy { it.subjectId } }
    val subjectById = remember(subjects) { subjects.associateBy { it.id } }

    // 计数全部来自内存中的当前考试数据：万条记录也只做一次分组，不发额外查询。
    val recordsByCategory = remember(state.sessions) { state.sessions.groupingBy { it.categoryId }.eachCount() }
    val recordsBySubject = remember(state.sessions) { state.sessions.groupingBy { it.subjectId }.eachCount() }
    val batchesByCategory = remember(state.wrongBatches) { state.wrongBatches.groupingBy { it.categoryId }.eachCount() }
    val batchesBySubject = remember(state.wrongBatches) { state.wrongBatches.groupingBy { it.subjectId }.eachCount() }
    val prescriptionsByCategory = remember(state.prescriptions) {
        state.prescriptions.groupingBy { it.categoryId }.eachCount()
    }
    val prescriptionsBySubject = remember(prescriptionsByCategory, state.categories) {
        val subjectOfCategory = state.categories.associate { it.id to it.subjectId }
        prescriptionsByCategory.entries.groupingBy { subjectOfCategory[it.key] ?: 0L }
            .fold(0) { sum, entry -> sum + entry.value }
    }

    fun categoryReferences(categoryId: Long) = CatalogRules.References(
        records = recordsByCategory[categoryId] ?: 0,
        batches = batchesByCategory[categoryId] ?: 0,
        prescriptions = prescriptionsByCategory[categoryId] ?: 0,
    )

    fun subjectReferences(subjectId: Long) = CatalogRules.References(
        records = recordsBySubject[subjectId] ?: 0,
        batches = batchesBySubject[subjectId] ?: 0,
        prescriptions = prescriptionsBySubject[subjectId] ?: 0,
    )

    var expandedSubjectId by rememberSaveable { mutableLongStateOf(0L) }
    var actingSubject by remember { mutableStateOf<Subject?>(null) }
    var actingCategory by remember { mutableStateOf<Category?>(null) }
    var renamingSubject by remember { mutableStateOf<Subject?>(null) }
    var renamingCategory by remember { mutableStateOf<Category?>(null) }
    var deletingSubject by remember { mutableStateOf<Subject?>(null) }
    var deletingCategory by remember { mutableStateOf<Category?>(null) }
    var creatingSubject by remember { mutableStateOf(false) }
    var creatingCategoryUnder by remember { mutableStateOf<Subject?>(null) }

    val activeCategories = remember(state.categories, subjects) {
        val activeSubjectIds = subjects.filterNot { it.archived }.map { it.id }.toSet()
        state.categories.filter { !it.archived && it.subjectId in activeSubjectIds }
    }
    val archivedCategories = remember(state.categories, subjects) {
        val activeSubjectIds = subjects.filterNot { it.archived }.map { it.id }.toSet()
        state.categories.filter { it.archived && it.subjectId in activeSubjectIds }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item { PageHeading("科目与类别", "分类管理") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "按当前考试「${state.exam.displayName}」整理。归档只隐藏选择入口，历史记录、看板统计、CSV 与备份都不受影响。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "预置科目与类别是统计和导出的公共基准，不能改名或删除；需要别的叫法可以归档后自建。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { SectionLabel("启用中的科目") }
            item {
                Text(
                    "${activeSubjects.size} 个科目 · ${activeCategories.size} 个启用类别",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (activeSubjects.isEmpty()) {
                item {
                    NoteCard(
                        "没有启用科目",
                        "从下方「已归档」里恢复一个，或直接新建；没有科目就无法开始计时与补记。",
                    )
                }
            }
            items(activeSubjects, key = { "subject-${it.id}" }) { subject ->
                val categories = categoriesBySubject[subject.id].orEmpty()
                val active = categories.filterNot { it.archived }
                val archivedCount = categories.count { it.archived }
                SubjectBlock(
                    subject = subject,
                    activeCategories = active,
                    archivedCount = archivedCount,
                    recordCount = recordsBySubject[subject.id] ?: 0,
                    expanded = expandedSubjectId == subject.id,
                    busy = busy,
                    onToggle = {
                        expandedSubjectId = if (expandedSubjectId == subject.id) 0L else subject.id
                    },
                    onActions = { actingSubject = subject },
                    onCategoryActions = { actingCategory = it },
                    onCreateCategory = { creatingCategoryUnder = subject },
                    recordCountOf = { id -> recordsByCategory[id] ?: 0 },
                )
            }

            if (archivedSubjects.isNotEmpty() || archivedCategories.isNotEmpty()) {
                item { SectionLabel("已归档") }
                item {
                    Text(
                        "归档项不在计时、补记与记录筛选里出现；恢复后追加到列表末尾。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(archivedSubjects, key = { "archived-subject-${it.id}" }) { subject ->
                    val archivedCount = categoriesBySubject[subject.id].orEmpty().count { it.archived }
                    ArchivedRow(
                        title = subject.name,
                        note = buildString {
                            append(if (subject.isPreset) "预置科目" else "自建科目")
                            append(" · ${categoriesBySubject[subject.id].orEmpty().size} 个类别")
                            if (archivedCount > 0) append("（其中 $archivedCount 个也已归档）")
                        },
                        busy = busy,
                    ) { model.setSubjectArchived(subject.id, false) }
                }
                items(archivedCategories, key = { "archived-category-${it.id}" }) { category ->
                    ArchivedRow(
                        title = "${subjectById[category.subjectId]?.name ?: "未命名科目"} · ${category.name}",
                        note = "已归档类别 · ${recordsByCategory[category.id] ?: 0} 条记录",
                        busy = busy,
                    ) { model.setCategoryArchived(category.id, false) }
                }
            }

            item {
                OutlinedButton(
                    onClick = { creatingSubject = true },
                    enabled = !busy,
                    shape = RoundedCornerShape(2.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text("＋ 新建科目", style = MaterialTheme.typography.titleMedium) }
            }
            item {
                Text(
                    "新建的科目归到当前考试；类别在对应科目里新建。改名与排序只影响显示，不重写任何历史记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    actingSubject?.let { subject ->
        val position = activeSubjects.indexOfFirst { it.id == subject.id }
        val deleteProblem = CatalogRules.deleteError(subject.isPreset, subjectReferences(subject.id))
        CatalogActionDialog(
            title = subject.name,
            subtitle = "科目 · ${if (subject.isPreset) "预置" else "自建"} · " +
                "${categoriesBySubject[subject.id].orEmpty().count { !it.archived }} 个启用类别",
            actions = listOf(
                CatalogAction(
                    label = "改名",
                    note = if (subject.isPreset) "预置科目不可改名" else null,
                    enabled = !subject.isPreset && !busy,
                ) {
                    actingSubject = null
                    renamingSubject = subject
                },
                CatalogAction(
                    label = "上移",
                    note = if (position <= 0) "已经在最前" else null,
                    enabled = position > 0 && !busy,
                ) {
                    actingSubject = null
                    model.moveSubject(subject.id, -1)
                },
                CatalogAction(
                    label = "下移",
                    note = if (position >= activeSubjects.lastIndex) "已经在最后" else null,
                    enabled = position in 0 until activeSubjects.lastIndex && !busy,
                ) {
                    actingSubject = null
                    model.moveSubject(subject.id, 1)
                },
                CatalogAction(
                    label = "归档",
                    note = if (activeSubjects.size <= 1) "至少保留一个启用科目" else "只隐藏入口，记录与统计不变",
                    enabled = activeSubjects.size > 1 && !busy,
                ) {
                    actingSubject = null
                    model.setSubjectArchived(subject.id, true)
                },
                CatalogAction(
                    label = "删除",
                    note = deleteProblem ?: "无记录、错题袋与处方引用，可删除",
                    enabled = deleteProblem == null && !busy,
                    destructive = true,
                ) {
                    actingSubject = null
                    deletingSubject = subject
                },
            ),
            onDismiss = { actingSubject = null },
        )
    }

    actingCategory?.let { category ->
        val siblings = categoriesBySubject[category.subjectId].orEmpty().filterNot { it.archived }
        val position = siblings.indexOfFirst { it.id == category.id }
        val subjectArchived = subjectById[category.subjectId]?.archived == true
        val deleteProblem = CatalogRules.deleteError(category.isPreset, categoryReferences(category.id))
        CatalogActionDialog(
            title = category.name,
            subtitle = "类别 · ${if (category.isPreset) "预置" else "自建"} · " +
                "${recordsByCategory[category.id] ?: 0} 条记录",
            actions = listOf(
                CatalogAction(
                    label = "改名",
                    note = if (category.isPreset) "预置类别不可改名" else null,
                    enabled = !category.isPreset && !busy,
                ) {
                    actingCategory = null
                    renamingCategory = category
                },
                CatalogAction(
                    label = "上移",
                    note = if (position <= 0) "已经在最前" else null,
                    enabled = position > 0 && !busy,
                ) {
                    actingCategory = null
                    model.moveCategory(category.id, -1)
                },
                CatalogAction(
                    label = "下移",
                    note = if (position >= siblings.lastIndex) "已经在最后" else null,
                    enabled = position in 0 until siblings.lastIndex && !busy,
                ) {
                    actingCategory = null
                    model.moveCategory(category.id, 1)
                },
                CatalogAction(
                    label = "归档",
                    note = if (!subjectArchived && siblings.size <= 1) {
                        "这个科目至少要保留一个启用类别"
                    } else {
                        "只隐藏入口，记录与统计不变"
                    },
                    enabled = (subjectArchived || siblings.size > 1) && !busy,
                ) {
                    actingCategory = null
                    model.setCategoryArchived(category.id, true)
                },
                CatalogAction(
                    label = "删除",
                    note = deleteProblem ?: "无记录、错题袋与处方引用，可删除",
                    enabled = deleteProblem == null && !busy,
                    destructive = true,
                ) {
                    actingCategory = null
                    deletingCategory = category
                },
            ),
            onDismiss = { actingCategory = null },
        )
    }

    renamingSubject?.let { subject ->
        CatalogNameDialog(
            title = "给科目改名",
            initial = subject.name,
            hint = "改名不改变历史记录、统计与导出；它们都按 ID 关联。",
            confirmLabel = "保存",
            validate = { raw ->
                CatalogRules.renameError(
                    rawName = raw,
                    siblings = subjects.map { it.id to it.name },
                    selfId = subject.id,
                    isPreset = subject.isPreset,
                )
            },
            onDismiss = { renamingSubject = null },
        ) { raw ->
            model.renameSubject(subject.id, raw)
            renamingSubject = null
        }
    }

    renamingCategory?.let { category ->
        CatalogNameDialog(
            title = "给类别改名",
            initial = category.name,
            hint = "只改显示名称；记录、错题袋与处方都按 ID 关联。",
            confirmLabel = "保存",
            validate = { raw ->
                CatalogRules.renameError(
                    rawName = raw,
                    siblings = categoriesBySubject[category.subjectId].orEmpty().map { it.id to it.name },
                    selfId = category.id,
                    isPreset = category.isPreset,
                )
            },
            onDismiss = { renamingCategory = null },
        ) { raw ->
            model.renameCategory(category.id, raw)
            renamingCategory = null
        }
    }

    if (creatingSubject) {
        CatalogNameDialog(
            title = "新建科目",
            initial = "",
            hint = "新科目归到当前考试「${state.exam.displayName}」，建完记得在里面加类别。",
            confirmLabel = "新建",
            validate = { raw ->
                CatalogRules.renameError(
                    rawName = raw,
                    siblings = subjects.map { it.id to it.name },
                    selfId = -1,
                    isPreset = false,
                )
            },
            onDismiss = { creatingSubject = false },
        ) { raw ->
            creatingSubject = false
            model.createSubject(raw) { id -> expandedSubjectId = id }
        }
    }

    creatingCategoryUnder?.let { subject ->
        CatalogNameDialog(
            title = "在「${subject.name}」下新建类别",
            initial = "",
            hint = "同名类别只在这个科目内判重；建完会出现在计时与补记的下拉里。",
            confirmLabel = "新建",
            validate = { raw ->
                CatalogRules.renameError(
                    rawName = raw,
                    siblings = categoriesBySubject[subject.id].orEmpty().map { it.id to it.name },
                    selfId = -1,
                    isPreset = false,
                )
            },
            onDismiss = { creatingCategoryUnder = null },
        ) { raw ->
            creatingCategoryUnder = null
            expandedSubjectId = subject.id
            model.createCategory(subject.id, raw) { }
        }
    }

    deletingSubject?.let { subject ->
        val childCount = categoriesBySubject[subject.id].orEmpty().size
        AlertDialog(
            onDismissRequest = { deletingSubject = null },
            title = { Text("删除科目「${subject.name}」？") },
            text = {
                Text(
                    buildString {
                        append("删除后无法恢复。")
                        if (childCount > 0) append("科目下的 $childCount 个类别也会一起删除。")
                        append("如果只是不想在列表里看到它，建议改为归档：历史记录与统计都不受影响。")
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = subject.id
                    deletingSubject = null
                    model.deleteSubject(id)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingSubject = null }) { Text("取消") } },
        )
    }

    deletingCategory?.let { category ->
        AlertDialog(
            onDismissRequest = { deletingCategory = null },
            title = { Text("删除类别「${category.name}」？") },
            text = {
                Text("删除后无法恢复。如果只是不想在列表里看到它，建议改为归档：历史记录与统计都不受影响。")
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = category.id
                    deletingCategory = null
                    model.deleteCategory(id)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingCategory = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SubjectBlock(
    subject: Subject,
    activeCategories: List<Category>,
    archivedCount: Int,
    recordCount: Int,
    expanded: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onActions: () -> Unit,
    onCategoryActions: (Category) -> Unit,
    onCreateCategory: () -> Unit,
    recordCountOf: (Long) -> Int,
) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(subject.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (subject.isPreset) "预置" else "自建",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    buildString {
                        append("${activeCategories.size} 个类别 · $recordCount 条记录")
                        if (archivedCount > 0) append(" · $archivedCount 个已归档")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (expanded) "收起类别" else "展开类别",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onActions, enabled = !busy) {
                Icon(Icons.Default.MoreVert, contentDescription = "管理科目${subject.name}")
            }
        }
        if (expanded) {
            if (activeCategories.isEmpty()) {
                Text(
                    "这个科目还没有启用类别；建一个才能在这里计时。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
                )
            }
            activeCategories.forEach { category ->
                CategoryRow(category, recordCountOf(category.id), busy) { onCategoryActions(category) }
            }
            TextButton(
                onClick = onCreateCategory,
                enabled = !busy,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.padding(start = 8.dp).heightIn(min = 44.dp),
            ) { Text("＋ 在这个科目下新建类别", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun CategoryRow(category: Category, recordCount: Int, busy: Boolean, onActions: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = !busy, onClick = onActions)
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(category.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                buildString {
                    category.groupName?.takeIf { it.isNotBlank() }?.let { append("$it · ") }
                    append("$recordCount 条记录 · ${if (category.isPreset) "预置" else "自建"}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "管理",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ArchivedRow(title: String, note: String, busy: Boolean, restore: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = restore, enabled = !busy) { Text("恢复") }
        }
    }
}

/** 动作面板故意把不可用项也列出来，让「预置不能改名」这类规则可见，而不是凭空消失。 */
private data class CatalogAction(
    val label: String,
    val note: String? = null,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun CatalogActionDialog(
    title: String,
    subtitle: String,
    actions: List<CatalogAction>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                actions.forEachIndexed { index, action ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
                    }
                    val labelColor: Color = when {
                        !action.enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        action.destructive -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onBackground
                    }
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = action.enabled) { action.onClick() }
                            .padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(action.label, style = MaterialTheme.typography.bodyLarge, color = labelColor)
                        action.note?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (action.enabled) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun CatalogNameDialog(
    title: String,
    initial: String,
    hint: String,
    confirmLabel: String,
    validate: (String) -> String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // 以 initial 为键：连续给两个类别改名时，不能把上一个的名字带进输入框。
    var value by rememberSaveable(title, initial) { mutableStateOf(initial) }
    var error by rememberSaveable(title, initial) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; error = null },
                    label = { Text("名称") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { Text(error ?: "最多 ${NameRules.MAX_LENGTH} 个字。") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val problem = validate(value)
                if (problem != null) error = problem else onConfirm(value)
            }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
