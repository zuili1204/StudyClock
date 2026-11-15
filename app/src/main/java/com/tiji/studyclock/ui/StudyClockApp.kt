package com.tiji.studyclock.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tiji.studyclock.BuildConfig
import com.tiji.studyclock.data.backup.FolderBackup
import com.tiji.studyclock.data.export.CsvWriter
import com.tiji.studyclock.data.importer.ImportCandidate
import com.tiji.studyclock.data.importer.ImportField
import com.tiji.studyclock.data.importer.ImportResolution
import com.tiji.studyclock.data.repo.SettingsRepository
import com.tiji.studyclock.domain.diagnose.Insight
import com.tiji.studyclock.domain.model.*
import com.tiji.studyclock.domain.prescribe.PrescriptionEvaluator
import com.tiji.studyclock.domain.prescribe.PrescriptionPlanner
import com.tiji.studyclock.domain.stats.CalendarHeat
import com.tiji.studyclock.domain.stats.CategoryCoverage
import com.tiji.studyclock.domain.stats.ConfidenceLevel
import com.tiji.studyclock.domain.stats.CoverageMap
import com.tiji.studyclock.domain.stats.CoverageStatus
import com.tiji.studyclock.domain.stats.DayStats
import com.tiji.studyclock.domain.stats.DaySummary
import com.tiji.studyclock.domain.stats.DayTrack
import com.tiji.studyclock.domain.stats.HeatCell
import com.tiji.studyclock.domain.stats.HeatMetric
import com.tiji.studyclock.domain.stats.Metric
import com.tiji.studyclock.domain.stats.MonthRhythmRules
import com.tiji.studyclock.domain.stats.MonthSummary
import com.tiji.studyclock.domain.stats.RedoLedger
import com.tiji.studyclock.domain.stats.SubjectCoverage
import com.tiji.studyclock.domain.stats.TagCloudLayout
import com.tiji.studyclock.domain.stats.TagStat
import com.tiji.studyclock.domain.stats.Thresholds
import com.tiji.studyclock.domain.stats.TraceBar
import com.tiji.studyclock.domain.stats.TraceBars
import com.tiji.studyclock.domain.timer.FinishedTimer
import com.tiji.studyclock.domain.timer.SessionTimer
import com.tiji.studyclock.domain.timer.TimerStart
import com.tiji.studyclock.service.TimerService
import com.tiji.studyclock.ui.theme.StudyClockTheme
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyClockApp(
    state: AppState,
    busy: Boolean,
    model: MainViewModel,
    onPreviewTheme: (ThemeMode?) -> Unit = {},
    requestedRoute: String? = null,
    onRouteHandled: () -> Unit = {},
) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "today"
    val snackbar = remember { SnackbarHostState() }
    val undoSnackbar = remember { SnackbarHostState() }
    val pendingDeletion by model.pendingDeletion.collectAsStateWithLifecycle()
    val activeTimer by model.activeTimer.collectAsStateWithLifecycle()
    val finishedTimer by model.finishedTimer.collectAsStateWithLifecycle()
    val restorePreview by model.restorePreview.collectAsStateWithLifecycle()
    val importPreview by model.importPreview.collectAsStateWithLifecycle()
    LaunchedEffect(pendingDeletion) {
        pendingDeletion?.let { pending ->
            withTimeoutOrNull((pending.deadlineMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0)) {
                if (undoSnackbar.showSnackbar("记录已移除", "撤销", duration = SnackbarDuration.Indefinite) == SnackbarResult.ActionPerformed) {
                    model.undoDelete()
                }
            }
        }
    }
    val tabs = listOf("today" to "今日", "board" to "看板", "records" to "记录", "settings" to "我的")
    val tabIcons = listOf(Icons.Default.Home, Icons.Default.Star, Icons.Default.DateRange, Icons.Default.Settings)
    // v5：记录页的「记录 / 错题袋」分段提升到导航层，看板与今日卡可以直接跳到错题袋。
    var recordsTab by rememberSaveable { mutableStateOf("records") }
    val openBags: () -> Unit = {
        recordsTab = "bags"
        nav.navigate("records") {
            popUpTo(nav.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val isEntry = route.startsWith("entry")
    val isTimer = route.startsWith("timer")
    val isImport = route == "import"
    val isCoverage = route == "coverage"
    val isPrescription = route.startsWith("prescription")
    val isBag = route.startsWith("bag/")
    val isCatalog = route == "catalog"
    val isFullScreen = isEntry || isTimer || isImport || isCoverage || isPrescription || isBag || isCatalog
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refreshDate() }
    // 小组件深链：等 NavHost 设好导航图并产生当前页面后再导航；仅凭 loading 无法保证顺序。
    LaunchedEffect(requestedRoute, state.loading, state.error, entry) {
        if (requestedRoute != null && !state.loading && state.error == null && entry != null) {
            nav.navigate(requestedRoute) { launchSingleTop = true }
            onRouteHandled()
        }
    }
    LaunchedEffect(model) {
        model.events.collectLatest { event ->
            when (event) {
                UiEvent.RecordSaved -> {
                    nav.popBackStack()
                    snackbar.showSnackbar("练习已保存")
                }
                is UiEvent.Imported -> {
                    if (nav.currentBackStackEntry?.destination?.route == "import") nav.popBackStack()
                    snackbar.showSnackbar(event.text)
                }
                is UiEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(if (pendingDeletion != null) undoSnackbar else snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (isFullScreen) TopAppBar(
                title = {
                    Text(
                        when {
                            // 进行中的计时以记录本身的 focus 为准：从今日横幅点进来时路由仍是 "timer"，
                            // 只认路由会把专注计时叫成「计时练习」。未开始时才回退到路由判断。
                            isTimer -> if (activeTimer?.focus == true || route == "timer/focus") "专注计时" else "计时练习"
                            isImport -> "导入记录"
                            isCoverage -> "覆盖地图"
                            isPrescription -> "训练处方"
                            isBag -> "错题袋"
                            isCatalog -> "分类管理"
                            else -> "记录练习"
                        },
                    )
                },
                navigationIcon = { IconButton(onClick = { if (!busy) nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (!isFullScreen && !state.loading && state.error == null) {
                InkBar(current = route, tabs = tabs, icons = tabIcons) { target ->
                    nav.navigate(target) {
                        popUpTo(nav.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (state.error != null) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("数据没有读出来", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    state.error.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "记录仍保存在本机，重新读取不会改动已有数据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = model::retryLoad,
                    shape = RoundedCornerShape(2.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("重新读取") }
            }
        } else {
            NavHost(nav, startDestination = "today", modifier = Modifier.padding(padding)) {
                composable("today") {
                    TodayScreen(
                        state, activeTimer, finishedTimer,
                        { nav.navigate("timer") }, { nav.navigate("entry/0") },
                        { nav.navigate("settings") }, { nav.navigate("records") },
                        model::switchExam, model::dismissBackupReminder,
                        { nav.navigate("timer/focus") },
                        openBags,
                    )
                }
                composable("board") {
                    BoardScreen(
                        state,
                        onCoverage = { nav.navigate("coverage") },
                        onPrescription = { nav.navigate("prescription/$it") },
                        generatePrescriptions = model::generatePrescriptions,
                        openBags = openBags,
                    )
                }
                composable("coverage") {
                    CoverageScreen(state, model::generatePrescriptions)
                }
                composable("prescription/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { backStack ->
                    val id = backStack.arguments?.getLong("id") ?: 0L
                    PrescriptionDetail(
                        state = state,
                        prescription = state.prescriptions.find { it.id == id },
                        onStart = model::startPrescription,
                        onComplete = model::completePrescription,
                        onSkip = model::skipPrescription,
                        openTimer = { nav.navigate("timer") },
                        openBag = { batchId -> nav.navigate("bag/$batchId") },
                    )
                }
                composable("records") {
                    RecordsScreen(
                        state, model, recordsTab, { recordsTab = it },
                        { nav.navigate("timer") },
                        { nav.navigate("entry/0") },
                        { nav.navigate("entry/${it.id}") },
                        model::deleteSession,
                        { id -> nav.navigate("bag/$id") },
                    )
                }
                composable("bag/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { backStack ->
                    val id = backStack.arguments?.getLong("id") ?: 0L
                    WrongBatchDetailScreen(state, id, model) { nav.navigate("timer") { launchSingleTop = true } }
                }
                composable("settings") {
                    SettingsScreen(
                        state, busy, model::saveGoals, model::exportCsv, model::switchExam,
                        restorePreview, model::exportBackup, model::setBackupFolder, model::backupToFolder,
                        model::previewRestore,
                        model::cancelRestore, model::confirmRestore,
                        { nav.navigate("import") },
                        onPreviewTheme,
                        { nav.navigate("catalog") },
                    )
                }
                composable("catalog") {
                    CatalogScreen(state, busy, model)
                }
                composable("import") {
                    ImportScreen(
                        state, busy, importPreview, model::previewCsvImport,
                        model::remapImportField, model::resolveImportPair,
                        model::setImportSkipDuplicates, model::confirmCsvImport,
                        model::cancelCsvImport,
                    )
                }
                composable("timer") {
                    TimerScreen(
                        state, activeTimer, finishedTimer, busy, model.bootCount,
                        model::startTimer, model::startFocus, false,
                        model::endTimer, model::discardTimer, model::saveSession,
                        { nav.navigate("entry/0") },
                        model::pauseTimer, model::resumeTimer,
                        model::createSubject, model::createCategory,
                        model::setCustomTimerMinutes,
                        model::reportNotificationPermissionDenied,
                        model,
                    )
                }
                composable("timer/focus") {
                    TimerScreen(
                        state, activeTimer, finishedTimer, busy, model.bootCount,
                        model::startTimer, model::startFocus, true,
                        model::endTimer, model::discardTimer, model::saveSession,
                        { nav.navigate("entry/0") },
                        model::pauseTimer, model::resumeTimer,
                        model::createSubject, model::createCategory,
                        model::setCustomTimerMinutes,
                        model::reportNotificationPermissionDenied,
                        model,
                    )
                }
                composable("entry/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { backStack ->
                    val id = backStack.arguments?.getLong("id") ?: 0L
                    RecordEditor(state, state.sessions.find { it.id == id }, busy, model::saveSession, model::createSubject, model::createCategory, model)
                }
            }
        }
    }
    // v5：历史回填必须先预览再确认（D18）；对话框放在导航之外，任何页面都能触发。
    BackfillPreviewDialog(model)
}

/** 全站芯片统一：选中用墨蓝实底，未选保持纸面描边，避免 Material 默认的粉色容器。 */
@Composable
internal fun inkChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = Color.Transparent,
    labelColor = MaterialTheme.colorScheme.onBackground,
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
)

/**
 * 键盘弹出后把正在输入的那一栏滚进可视区。foundation 1.6 的 `verticalScroll` 节点不实现
 * bringIntoView 协议，输入框自己的“滚到可见”请求没有响应方，只能按窗口坐标自己算距离。
 * [viewportBottomPx] 由外层容器上报，键盘顶起容器时它变小，正好作为可视区下沿。
 */
private class ImeReveal(val scroll: ScrollState, private val scope: CoroutineScope, density: Density) {
    var viewportBottomPx by mutableIntStateOf(0)
    private val marginPx = with(density) { 12.dp.roundToPx() }

    fun reveal(fieldBottomPx: Int) {
        if (viewportBottomPx <= 0) return
        val overflow = fieldBottomPx - (viewportBottomPx - marginPx)
        if (overflow <= 0) return
        scope.launch { scroll.animateScrollTo((scroll.value + overflow).coerceAtMost(scroll.maxValue)) }
    }
}

@Composable
private fun rememberImeReveal(scroll: ScrollState): ImeReveal {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    return remember(scroll, scope, density) { ImeReveal(scroll, scope, density) }
}

/** 只在这一栏拿到焦点、且底部被键盘挡住时上滚；键盘收起或本来就完整可见时不动。 */
@Composable
private fun Modifier.revealWhenFocused(reveal: ImeReveal): Modifier {
    var focused by remember { mutableStateOf(false) }
    var bottomPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(focused, reveal.viewportBottomPx) {
        if (focused) reveal.reveal(bottomPx)
    }
    return this
        .onFocusChanged { focused = it.isFocused }
        .onGloballyPositioned { bottomPx = it.boundsInWindow().bottom.roundToInt() }
}

@Composable
internal fun PageHeading(eyebrow: String, title: String, trailing: @Composable (() -> Unit)? = null) {
    // 大字号下眉标与右上角限定语挤不进一行（200% 时两者合计远超一屏宽），
    // 与其让倒计时折成两行、贴着屏幕边缘，不如在 1.5 倍以上直接改成上下两行。
    val stacked = trailing != null && LocalDensity.current.fontScale >= 1.5f
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    eyebrow,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                trailing?.invoke()
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    eyebrow,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                trailing?.invoke()
            }
        }
        Text(title, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
    }
}

/** 底部导航：细线 + 纯文字，选中用一个短横线，而不是 Material 的胶囊与漂浮圆角。 */
@Composable
private fun InkBar(current: String, tabs: List<Pair<String, String>>, icons: List<ImageVector>, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
        Row(Modifier.fillMaxWidth().heightIn(min = 58.dp)) {
            tabs.forEachIndexed { index, tab ->
                val selected = current == tab.first
                val tint = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    // 高度取「内容高」而不是填满：底栏在 Scaffold 里没有高度上限，
                    // fillMaxHeight 会把它撑成整屏，正文就只剩 0 高。
                    Modifier.weight(1f).heightIn(min = 58.dp).clickable { onSelect(tab.first) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.width(18.dp).height(2.dp)
                            .background(if (selected) MaterialTheme.colorScheme.secondary else Color.Transparent),
                    )
                    Spacer(Modifier.height(8.dp))
                    Icon(icons[index], null, Modifier.size(19.dp), tint = tint)
                    Spacer(Modifier.height(4.dp))
                    Text(tab.second, style = MaterialTheme.typography.labelSmall, color = tint)
                }
            }
        }
    }
}

@Composable
internal fun NoteCard(title: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExamSwitchSheet(state: AppState, onDismiss: () -> Unit, onSelect: (ExamType) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("切换考试", style = MaterialTheme.typography.headlineSmall)
            Text(
                "科目、类别、记录、统计都按考试分开，互不混算。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            state.examSummaries.sortedByDescending { it.sessionCount }.forEach { summary ->
                val current = summary.type == state.currentExam
                Row(
                    Modifier.fillMaxWidth().testTag("exam-option-${summary.type.key}")
                        .clickable { onSelect(summary.type) }.padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            summary.name,
                            style = MaterialTheme.typography.titleLarge,
                            color = if (summary.sessionCount == 0 && !current) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            buildString {
                                append("${summary.sessionCount} 条 · ${summary.questionCount} 题")
                                summary.examDate?.let { append(" · 考试 ${it}") }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (current) {
                        Text("当前", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
            }
        }
    }
}

/**
 * 今日：一屏回答两件事——现在该做什么，以及今天已经留下什么。
 *
 * 改造前页顶依次排着「待复做」「专注」两条同权重横幅、一行考试切换、一张备份提醒，
 * 主卡要滚三屏才看得到。现在这四条合成一条「接力」：只显示当前最该接上的那个动作，
 * 专注入口退到主卡里的次要按钮——它不再和主行动抢位置，但仍然一步可达。
 */
@Composable
private fun TodayScreen(
    state: AppState,
    running: TimerStart?,
    finished: FinishedTimer?,
    start: () -> Unit,
    add: () -> Unit,
    settings: () -> Unit,
    records: () -> Unit,
    switchExam: (ExamType) -> Unit,
    dismissBackup: () -> Unit,
    startFocus: () -> Unit,
    openBags: () -> Unit,
) {
    var switching by remember { mutableStateOf(false) }
    if (switching) ExamSwitchSheet(state, onDismiss = { switching = false }) { type ->
        switching = false
        switchExam(type)
    }
    val today = state.today
    val todaySessions = state.sessions.filter { sessionDate(it, state.settings.dateAttribution) == today }
    val todaySummary = DayStats.summarize(todaySessions, today, state.settings.dateAttribution)
    val examDays = state.exam.examDate?.let { ChronoUnit.DAYS.between(today, it) }
    val pendingRedo = state.redo?.pendingQuestions ?: 0

    LazyColumn(
        modifier = Modifier.testTag("today-list"),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            PageHeading(today.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)), "每一题，都有迹可循") {
                Row(
                    Modifier.testTag("exam-switch").minimumInteractiveComponentSize()
                        .clickable { switching = true }
                        .semantics { contentDescription = "切换考试" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Box(Modifier.size(5.dp).background(MaterialTheme.colorScheme.secondary))
                    Text(
                        "${state.exam.displayName} · " + when {
                            examDays == null -> "未设日期"
                            examDays < 0 -> "已过"
                            examDays == 0L -> "今天"
                            else -> "还有 $examDays 天"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        }
        // 接力：同一时刻只会有一件事真正「接着做」，所以这里只放一条。
        when {
            running != null -> item {
                HandoffRow(
                    title = if (running.focus) "专注进行中" else "计时进行中",
                    detail = "${state.categories.find { it.id == running.categoryId }?.name ?: "已选类别"} · ${timeOfDay(running.startAt)} 开始",
                    action = "回到计时",
                    onClick = start,
                )
            }
            finished != null -> item {
                HandoffRow(
                    title = "计时已结束",
                    detail = "${state.categories.find { it.id == finished.categoryId }?.name ?: "已选类别"} · 用时 ${formatElapsed(finished.durationSec.toLong())}，待填写结果",
                    action = "去填写",
                    onClick = start,
                )
            }
            pendingRedo > 0 -> item {
                HandoffRow(
                    title = "还有 $pendingRedo 道错题待复做",
                    detail = "${state.redo?.pendingBatches ?: 0} 个错题袋 · 复做不抬高主正确率",
                    action = "去复做",
                    onClick = openBags,
                )
            }
        }
        item {
            TodayCard(
                questions = state.todayStats?.totalQuestions ?: 0,
                sessionCount = todaySessions.size,
                summary = todaySummary,
                settings = state.settings,
                start = start,
                add = add,
                startFocus = startFocus,
            )
        }
        if (state.backup.reminderDue) item {
            Column(
                Modifier.fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    if (state.backup.reminderNever) "这些记录还没有备份过"
                    else "距上次备份 ${state.backup.daysSinceBackup ?: 0} 天",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "记录只保存在这台设备；导出备份或设置备份文件夹，换机时才能带回。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismissBackup, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("稍后", style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(onClick = settings, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("去备份", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("今天的足迹", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = records, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("全部记录", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (todaySessions.isEmpty()) item { NoteCard("还没有记录", "完成一组后记下来，间隔与时段会成为判断依据。") }
        items(todaySessions.take(3), key = { it.id }) { session -> SessionCard(session, state) }
        item {
            Text(
                "仅存于这台设备 · 无网络权限 · 不采集设备信息",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 接力条：一个动作、一行说明、一个动词。没有第二条横幅和它抢同一件事。 */
@Composable
private fun HandoffRow(title: String, detail: String, action: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(2.dp).height(38.dp).background(MaterialTheme.colorScheme.secondary))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(action, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
    }
}

/**
 * 今日主卡：一个数字（题量）、三个事实（次数 / 正确率 / 时长）、一个目标、两个动作。
 *
 * 原来卡里塞了七行文字（含关键词与「每日目标」），主按钮被挤到卡片下沿之外；
 * 现在把关键词移走、把目标改成进度条，动作回到拇指可达的位置。
 */
@Composable
private fun TodayCard(
    questions: Int,
    sessionCount: Int,
    summary: DaySummary,
    settings: UserSettings,
    start: () -> Unit,
    add: () -> Unit,
    startFocus: () -> Unit,
) {
    val onInk = MaterialTheme.colorScheme.onPrimary
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
            .padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("今天", style = MaterialTheme.typography.labelLarge, color = onInk.copy(alpha = 0.78f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text("$questions", style = MaterialTheme.typography.displayLarge, color = onInk)
            Text(
                " 题",
                style = MaterialTheme.typography.bodyMedium,
                color = onInk.copy(alpha = 0.78f),
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Box(Modifier.weight(1f))
            Text(
                "$sessionCount",
                style = MaterialTheme.typography.displaySmall,
                color = onInk,
                modifier = Modifier.padding(bottom = 2.dp),
            )
            Text(
                " 次",
                style = MaterialTheme.typography.bodyMedium,
                color = onInk.copy(alpha = 0.78f),
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        val facts = buildList {
            if (sessionCount > 0) {
                if (summary.performanceQuestions > 0) add("正确率 ${accuracyText(summary.accuracy)}")
                if (summary.timedSeconds > 0) add("计时 ${compactDuration(summary.timedSeconds)}")
                if (summary.focusSeconds > 0) add("专注 ${compactDuration(summary.focusSeconds)}")
            }
        }
        Text(
            if (facts.isEmpty()) "今天还没有记录" else facts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = onInk.copy(alpha = 0.8f),
        )
        val questionGoal = settings.dailyQuestionGoal?.takeIf { it > 0 }
        val minuteGoal = settings.dailyMinuteGoal?.takeIf { it > 0 }
        if (questionGoal != null || minuteGoal != null) {
            val parts = buildList {
                questionGoal?.let { goal ->
                    add(if (questions >= goal) "题量 $goal 已达成" else "题量 $goal · 还差 ${goal - questions}")
                }
                minuteGoal?.let { goal ->
                    val minutes = (summary.timedSeconds / 60).toInt()
                    add(if (minutes >= goal) "时长 $goal 分钟已达成" else "时长 $goal 分钟 · 还差 ${goal - minutes}")
                }
            }
            Text(
                parts.joinToString("　"),
                style = MaterialTheme.typography.bodySmall,
                color = onInk.copy(alpha = 0.78f),
            )
            if (questionGoal != null) {
                Box(Modifier.fillMaxWidth().height(3.dp).background(onInk.copy(alpha = 0.24f))) {
                    Box(
                        Modifier.fillMaxWidth((questions.toFloat() / questionGoal).coerceIn(0f, 1f))
                            .height(3.dp).background(onInk),
                    )
                }
            }
        }
        HorizontalDivider(color = onInk.copy(alpha = 0.16f), thickness = 1.dp)
        Button(
            onClick = start,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(2.dp),
            colors = ButtonDefaults.buttonColors(containerColor = onInk, contentColor = MaterialTheme.colorScheme.primary),
        ) { Text("开始计时", style = MaterialTheme.typography.titleMedium) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = add, modifier = Modifier.heightIn(min = 44.dp)) {
                Text("补记一次", style = MaterialTheme.typography.bodyMedium, color = onInk.copy(alpha = 0.88f))
            }
            TextButton(onClick = startFocus, modifier = Modifier.heightIn(min = 44.dp)) {
                Text("开始专注", style = MaterialTheme.typography.bodyMedium, color = onInk.copy(alpha = 0.88f))
            }
        }
    }
}

/** 发现区：规则命中的结论，按“需要注意 → 中性 → 正向”排序。 */

/**
 * 月历节律：看板第一块。
 *
 * 默认是「痕迹」视图：每天四根条（题 / 分 / 复 / 专），条长 = 当天该轨的量、深浅 = 该轨的质量，
 * 四条轨同屏，不需要切口径。单口径热力保留为第二视图，用来整月看某一条轨的强弱分布。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MonthHeatmap(state: AppState) {
    val attribution = state.settings.dateAttribution
    var monthOffset by rememberSaveable { mutableIntStateOf(0) }
    var thermal by rememberSaveable { mutableStateOf(false) }
    var metricName by rememberSaveable { mutableStateOf(HeatMetric.QUESTIONS.name) }
    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }
    val metric = HeatMetric.entries.firstOrNull { it.name == metricName } ?: HeatMetric.QUESTIONS
    val month = YearMonth.from(state.today).minusMonths(monthOffset.toLong())
    val monthStart = month.atDay(1)
    val days = month.lengthOfMonth()
    // 切口径、开面板、换视图都会重组；按「记录 / 月份 / 归属」记忆，避免每次重组都重算整月。
    val dayStats = remember(state.sessions, month, attribution) { DayStats.byDay(state.sessions, month, attribution) }
    val monthSummary = remember(state.sessions, month, attribution) { DayStats.month(state.sessions, month, attribution) }
    val rhythm = remember(dayStats, month, state.today) { MonthRhythmRules.of(dayStats.keys, month, state.today) }
    val traceScale = remember(dayStats) { TraceBars.scale(dayStats) }
    val monthMax = remember(dayStats, metric) { CalendarHeat.monthMax(dayStats, metric) }
    val goalLine = calendarGoalLine(state, dayStats)

    selectedDay?.let { day -> HeatDaySheet(state, day) { selectedDay = null } }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${month.year} 年 ${month.monthValue} 月",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            if (monthOffset > 0) {
                Text(
                    "回到本月",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.minimumInteractiveComponentSize().clickable { monthOffset = 0 },
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(48.dp).semantics { contentDescription = "上个月" }.clickable { monthOffset++ },
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", style = MaterialTheme.typography.titleMedium, modifier = Modifier.clearAndSetSemantics { })
            }
            Box(
                Modifier.size(48.dp).semantics { contentDescription = "下个月" }
                    .clickable(enabled = monthOffset > 0) { monthOffset-- },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "›",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (monthOffset == 0) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f) else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val leading = monthStart.dayOfWeek.value - 1
        var day = 1
        var row = 0
        while (row * 7 < leading + days) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(7) { column ->
                    val index = row * 7 + column
                    if (index < leading || day > days) {
                        Spacer(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val date = monthStart.withDayOfMonth(day)
                        val stat = dayStats[date]
                        if (thermal) {
                            ThermalDayCell(
                                date = date,
                                summary = stat,
                                cell = CalendarHeat.cell(stat, metric, monthMax),
                                metric = metric,
                                today = state.today,
                                onOpen = { selectedDay = date },
                            )
                        } else {
                            TraceDayCell(
                                date = date,
                                summary = stat,
                                bars = TraceBars.bars(stat, traceScale),
                                today = state.today,
                                onOpen = { selectedDay = date },
                            )
                        }
                        day++
                    }
                }
            }
            row++
        }
        // 视图切换放在图下面：先把整月的痕迹看到，再决定要不要切成单轨热力。
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeatViewChip("痕迹", !thermal, "四轨同屏") { thermal = false }
            HeatViewChip("热力", thermal, "单轨强度") { thermal = true }
            Spacer(Modifier.weight(1f))
            if (thermal) {
                Text(
                    "按${metric.label}着色",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        if (thermal) {
            Row(Modifier.fillMaxWidth().testTag("heatmap-metrics"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeatMetric.entries.forEach { option ->
                    val selected = option == metric
                    Box(
                        Modifier.weight(1f).heightIn(min = 48.dp)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                RoundedCornerShape(2.dp),
                            )
                            .border(
                                1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(2.dp),
                            )
                            .testTag("heatmap-metric-${option.name.lowercase(Locale.ROOT)}")
                            .clickable { metricName = option.name },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            option.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (metric.isRate) "低" else "少", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(0.3f, 0.45f, 0.8f, 1f).forEach { alpha ->
                    Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = alpha), RoundedCornerShape(2.dp)))
                }
                Text(if (metric.isRate) "高" else "多", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "${metric.note}；描边表示有记录、但没有这一轨的样本。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            TraceLegend(dayStats)
        }
        Text(
            buildString {
                append("${rhythm.trackedDays} / ${rhythm.elapsedDays} 天有记录")
                if (rhythm.elapsedDays > 0) append("（${(rhythm.coverage * 100).roundToInt()}%）")
                if (rhythm.longestRun > 1) append(" · 最长连续 ${rhythm.longestRun} 天")
                if (rhythm.gapCount > 0) append(" · 中断 ${rhythm.gapCount} 段，最长 ${rhythm.longestGap} 天")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (monthSummary.activeDays == 0) {
                "这个月还没有记录；今天要留下一道痕迹，只需要一次计时或一次补记。"
            } else {
                calendarTrackFacts(monthSummary)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (goalLine != null) {
            Text(goalLine, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

/**
 * 月历格里的日号：跟随字号放大，但封顶 14sp。
 *
 * 格子是 1:1 的，宽度由「一行七天」定死；系统字号拉到 200% 时，9sp 会变成 18dp，
 * 再加四根条就撑破方格，数字和条会叠在一起。这里只让它在 9→14sp 之间放大：
 * 需要更大字号时，点开某天看当天明细（明日号与其内容是完整缩放的）。
 */
@Composable
private fun calendarNumberSize(scale: Float): TextUnit =
    (min(9f * scale, 14f) / scale).sp

/** 一格四根条：日期在上，四轨条在下；条长表量、深浅表质量，四轨永远同屏。 */
@Composable
private fun RowScope.TraceDayCell(
    date: LocalDate,
    summary: DaySummary?,
    bars: List<TraceBar>,
    today: LocalDate,
    onOpen: () -> Unit,
) {
    val future = date.isAfter(today)
    val hasRecords = summary?.hasRecords == true
    val numberColor = when {
        future -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        hasRecords -> MaterialTheme.colorScheme.onBackground
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    }
    val fontScale = LocalDensity.current.fontScale
    Box(
        Modifier.weight(1f).aspectRatio(1f)
            .background(
                when {
                    future -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
                    hasRecords -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    else -> Color.Transparent
                },
                RoundedCornerShape(2.dp),
            )
            .then(
                // 今天用朱砂细边标出：月历在页面顶部时，先要能定位「现在」。
                if (date == today) {
                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(2.dp))
                } else {
                    Modifier
                },
            )
            .testTag("heatmap-day-${date.dayOfMonth}")
            .semantics { contentDescription = traceDayDescription(date, summary, bars, today) }
            .clickable(enabled = hasRecords, onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 2.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("${date.dayOfMonth}", fontSize = calendarNumberSize(fontScale), lineHeight = calendarNumberSize(fontScale) * 1.1f, color = numberColor)
            if (!future) {
                Spacer(Modifier.height(1.5.dp))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.2.dp)) {
                    bars.forEach { bar -> TraceBarStrip(bar) }
                }
            }
        }
    }
}

/** 单口径热力的一格：整格着色 + 底部四点。 */
@Composable
private fun RowScope.ThermalDayCell(
    date: LocalDate,
    summary: DaySummary?,
    cell: HeatCell,
    metric: HeatMetric,
    today: LocalDate,
    onOpen: () -> Unit,
) {
    val band = (cell as? HeatCell.Level)?.band
    val hasRecords = summary?.hasRecords == true
    // 还没到的日子比「错过」更浅：本月才过一半时，不能把后半月的空格读成断档。
    val future = date.isAfter(today)
    val background = when {
        future -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        band == 0 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        band == 1 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        band == 2 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
        band == 3 -> MaterialTheme.colorScheme.primary
        // 有记录但当前口径无样本：保持纸面底色，用描边说明「不是没学」。
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val strong = band != null && band >= 2
    val fontScale = LocalDensity.current.fontScale
    val contentColor = when {
        future -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        strong -> MaterialTheme.colorScheme.onPrimary
        hasRecords -> MaterialTheme.colorScheme.onBackground
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier.weight(1f).aspectRatio(1f).background(background, RoundedCornerShape(2.dp))
            .then(
                when {
                    date == today -> Modifier.border(1.5.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(2.dp))
                    cell is HeatCell.NoSample -> Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
                    else -> Modifier
                },
            )
            .testTag("heatmap-day-${date.dayOfMonth}")
            .semantics { contentDescription = heatCellDescription(date, summary, cell, metric, today) }
            .clickable(enabled = hasRecords, onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${date.dayOfMonth}", fontSize = calendarNumberSize(fontScale), color = contentColor)
            Spacer(Modifier.height(2.dp))
            if (future) {
                Spacer(Modifier.height(3.dp))
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    DayTrack.entries.forEach { track ->
                        val present = summary?.has(track) == true
                        Box(
                            Modifier.size(3.dp).background(
                                if (present) contentColor else contentColor.copy(alpha = 0.18f),
                                RoundedCornerShape(50),
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一根条：底槽始终在，条长表示当天的量（开方压缩以保住小量）；题条与复条的深色段表示答对 / 通过的比例。
 *
 * 用「浅段 + 深段」而不是整条改透明度：2.5dp 的细条上，透明度差几乎看不出，
 * 而深色占了多少是可以量出来的——这就是正确率与通过率最直白的画法。
 */
@Composable
private fun TraceBarStrip(bar: TraceBar) {
    val color = traceColor(bar.track)
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(2.5.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f), RoundedCornerShape(1.dp)),
        contentAlignment = Alignment.CenterStart,
    ) {
        val full = maxWidth
        when {
            bar.ratio > 0.0 -> {
                // 条长按平方根压缩，而不是直接等比。
                // 日题量是长尾分布（本月 5 题到 191 题），线性等比会把「做了 5 题」压成 4dp 的点，
                // 和「有记录但无量」的标记长得一样——真机上 11 月 5 日就是这样被读成「没记录」的。
                // 开方只改变长度映射，保持单调：量大的那天一定更长，顺序不变。
                val length = sqrt(bar.ratio).toFloat().coerceIn(0.1f, 1f)
                val barWidth = full * length
                Box(
                    Modifier.width(barWidth).fillMaxHeight()
                        .background(color.copy(alpha = 0.34f), RoundedCornerShape(1.dp)),
                )
                val passed = bar.quality
                // 10dp 以下画不出「两段」：深段会缩成一个方块，反而像另立了一个标记。
                if (passed != null && passed > 0.0 && barWidth >= 10.dp) {
                    Box(
                        Modifier.width(barWidth * passed.toFloat()).fillMaxHeight()
                            .background(color, RoundedCornerShape(1.dp)),
                    )
                }
            }
            // 有记录但这条轨没有量：画在正中，而不是贴着左边缘——
            // 条永远从左边长出来，居中的点自然读成「这条轨今天有事，但没有量」。
            bar.present -> Box(
                Modifier.align(Alignment.Center).size(3.dp)
                    .background(color.copy(alpha = 0.55f), RoundedCornerShape(50)),
            )
        }
    }
}

/** 四轨各有固定色相：墨（题）/ 赭（分）/ 苔（复）/ 朱（专）。 */
@Composable
internal fun traceColor(track: DayTrack): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.4f
    return when (track) {
        DayTrack.OBJECTIVE -> MaterialTheme.colorScheme.primary
        DayTrack.SCORED -> if (dark) Color(0xFFC9A368) else Color(0xFF8A6438)
        DayTrack.REDO -> if (dark) Color(0xFF93B08B) else Color(0xFF4F6B4A)
        DayTrack.FOCUS -> MaterialTheme.colorScheme.secondary
    }
}

/** 视图切换：两枚等宽 chip，当前视图反白。 */
@Composable
private fun HeatViewChip(text: String, selected: Boolean, hint: String, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                RoundedCornerShape(2.dp),
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(2.dp),
            )
            .padding(horizontal = 14.dp)
            .semantics { contentDescription = "$text，$hint" }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground,
        )
    }
}

/**
 * 四轨图例：颜色、名称、本月天数。
 *
 * 天数直接挂在图例上，而不是另起一行——图例本来就在回答「这四条轨是什么」，
 * 「这个月主要练什么」是同一个问题。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TraceLegend(dayStats: Map<LocalDate, DaySummary>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DayTrack.entries.forEach { track ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.width(16.dp).height(3.dp).background(traceColor(track), RoundedCornerShape(1.dp)))
                    Text(
                        "${track.short} ${dayStats.values.count { it.has(track) }} 天",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            "条长＝当天的量，按平方根压缩（量大的那天一定更长）；分直接按得分率。题条与复条的深色段＝答对 / 通过的比例，太短时不画深段。点方格看当天明细。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
        )
    }
}

/** 痕迹格的朗读：四轨各念一遍，把「有记录但没有量」与「没这条轨」分开。 */
private fun traceDayDescription(
    date: LocalDate,
    summary: DaySummary?,
    bars: List<TraceBar>,
    today: LocalDate,
): String {
    val prefix = String.format(Locale.CHINA, "%d 月 %d 日", date.monthValue, date.dayOfMonth)
    if (date.isAfter(today)) return "$prefix，还没到"
    val label = if (date == today) "$prefix（今天）" else prefix
    if (summary == null || !summary.hasRecords) return "$label，没有记录"
    val parts = buildList {
        if (summary.objectiveSessions > 0) {
            add(
                buildString {
                    append("题 ${summary.performanceQuestions} 题")
                    summary.accuracy?.let { append("，正确率 ${accuracyText(it)}") }
                },
            )
        }
        if (summary.scoredSessions > 0) {
            add(
                buildString {
                    append("得分 ${summary.scoredSessions} 次")
                    summary.scoreRate?.let { append("，得分率 ${accuracyText(it)}") }
                },
            )
        }
        if (summary.redoSessions > 0) {
            add(
                buildString {
                    append("复做 ${summary.redoSessions} 次")
                    if (summary.redoQuestions > 0) append("、${summary.redoQuestions} 题")
                    summary.redoRate?.let { append("，通过率 ${accuracyText(it)}") }
                },
            )
        }
        if (summary.focusSessions > 0) add("专注 ${compactDuration(summary.focusSeconds)}")
    }
    if (parts.isEmpty()) return "$label，有 ${summary.sessionCount} 组记录，但没有可比较的量"
    val missing = bars.count { !it.present }
    val tail = if (missing > 0) "；另 ${missing} 条轨没有记录" else ""
    return "$label，${parts.joinToString("；")}$tail"
}

/** 当月节律里的达标天数：只用每日目标，不新建口径。 */
private fun calendarGoalLine(state: AppState, dayStats: Map<LocalDate, DaySummary>): String? {
    val questionGoal = state.settings.dailyQuestionGoal ?: 0
    val minuteGoal = state.settings.dailyMinuteGoal ?: 0
    if (questionGoal <= 0 && minuteGoal <= 0) return null
    val parts = buildList {
        if (questionGoal > 0) add("题量 $questionGoal 题：${dayStats.values.count { it.questions >= questionGoal }} 天达标")
        if (minuteGoal > 0) {
            val goalSeconds = minuteGoal * 60L
            add("时长 $minuteGoal 分钟：${dayStats.values.count { it.timedSeconds >= goalSeconds }} 天达标")
        }
    }
    return parts.joinToString(" · ")
}

/** 月度三轨事实：缺样本的那条直接不出现，不写 0%。 */
private fun calendarTrackFacts(summary: MonthSummary): String = buildList {
    if (summary.performanceQuestions > 0) {
        add("客观题 ${summary.performanceQuestions} 题 · ${accuracyText(summary.accuracy)}")
    }
    if (summary.scoredSessions > 0) {
        add("得分 ${summary.scoredSessions} 次 · ${accuracyText(summary.scoreRate)}")
    }
    if (summary.redoSessions > 0) {
        add(
            buildString {
                append("复做 ${summary.redoSessions} 次")
                summary.redoRate?.let { append(" · ${accuracyText(it)}") }
            },
        )
    }
    if (summary.focusSeconds > 0) add("专注 ${compactDuration(summary.focusSeconds)}")
}.joinToString(" · ")

/** 方格的朗读文本：先口径值，再说当天有哪几类记录，最后说明「没样本」「没记录」「还没到」的区别。 */
private fun heatCellDescription(
    date: LocalDate,
    summary: DaySummary?,
    cell: HeatCell,
    metric: HeatMetric,
    today: LocalDate,
): String {
    val prefix = String.format(Locale.CHINA, "%d 月 %d 日", date.monthValue, date.dayOfMonth)
    if (date.isAfter(today)) return "$prefix，还没到"
    val label = if (date == today) "$prefix（今天）" else prefix
    if (cell is HeatCell.NoRecord || summary == null) return "$label，没有记录"
    val tracks = DayTrack.entries.filter { summary.has(it) }.joinToString("、") { it.label }
    val value = when (metric) {
        HeatMetric.QUESTIONS -> if (cell is HeatCell.NoSample) "没有题量" else "${summary.questions} 题"
        HeatMetric.MINUTES -> if (summary.timedSeconds > 0) "用时 ${compactDuration(summary.timedSeconds)}" else "没有计时"
        HeatMetric.ACCURACY -> if (summary.accuracy == null) "没有正确率样本" else "正确率 ${accuracyText(summary.accuracy)}"
        HeatMetric.SCORE -> if (summary.scoreRate == null) "没有得分样本" else "得分率 ${accuracyText(summary.scoreRate)}"
    }
    return buildString {
        append(label)
        append("，")
        if (cell is HeatCell.NoSample) {
            append("有 ${summary.sessionCount} 组记录，$value")
        } else {
            append(value)
        }
        if (tracks.isNotEmpty()) append("；含 $tracks")
    }
}

/** 当天面板：构成、目标与处方执行情况都在这一屏说清，再往下才是逐条记录。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun HeatDaySheet(state: AppState, day: LocalDate, onDismiss: () -> Unit) {
    val attribution = state.settings.dateAttribution
    val rows = state.sessions.filter { sessionDate(it, attribution) == day }.sortedByDescending { it.startAt }
    val summary = DayStats.summarize(rows, day, attribution)
    val dayTags = rows.flatMap { TagRules.parse(it.wrongTags) }.distinct()
    val byCategory = rows.groupBy { it.categoryId }
        .map { (categoryId, list) -> categoryId to DayStats.summarize(list, day, attribution) }
        .sortedByDescending { it.second.questions }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                String.format(Locale.CHINA, "%d 月 %d 日", day.monthValue, day.dayOfMonth),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                buildList {
                    add("${summary.sessionCount} 组")
                    if (summary.questions > 0) add("${summary.questions} 题")
                    // 缺轨不写 0：只有得分/复做/专注的一天，没有「答对」这个概念。
                    if (summary.performanceQuestions > 0) {
                        add("答对 ${summary.correct}")
                        add("正确率 ${accuracyText(summary.accuracy)}")
                    }
                }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val composition = dayComposition(summary)
            if (composition.isNotEmpty()) {
                Text("构成 · $composition", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                if (summary.timedSeconds > 0) {
                    "总用时 ${compactDuration(summary.timedSeconds)} · ${summary.timedSessions} 组计时"
                } else {
                    "没有记录用时；补记可以只留下日期与结果。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            dayGoalLines(summary, state.settings).forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            if (summary.prescriptionSessions > 0) {
                Text(
                    "其中 ${summary.prescriptionSessions} 次按处方执行",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (dayTags.isNotEmpty()) {
                Text("当天错题关键词", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    dayTags.forEach { tag -> TagPill(tag) }
                }
            }
            if (byCategory.size > 1) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
                Text("按类别", style = MaterialTheme.typography.labelLarge)
                byCategory.forEach { (categoryId, stats) ->
                    val name = state.categories.find { it.id == categoryId }?.name ?: "已归档类别"
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(
                            buildString {
                                append("${stats.questions} 题 · ${accuracyText(stats.accuracy)}")
                                if (stats.timedSeconds > 0) append(" · ${compactDuration(stats.timedSeconds)}")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            rows.forEach { session -> SessionCard(session, state) }
        }
    }
}

/** 当天由什么构成：只列出现的类型，题量/得分/通过率各说各的，不合并。 */
private fun dayComposition(summary: DaySummary): String = buildList {
    if (summary.objectiveSessions > 0) {
        add("正确率记录 ${summary.objectiveSessions} 次 ${summary.performanceQuestions} 题")
    }
    if (summary.scoredSessions > 0) {
        add("得分记录 ${summary.scoredSessions} 次 ${accuracyText(summary.scoreRate)}")
    }
    if (summary.redoSessions > 0) {
        add(
            buildString {
                append("错题复做 ${summary.redoSessions} 次")
                if (summary.redoQuestions > 0) append(" ${summary.redoCorrect}/${summary.redoQuestions}")
            },
        )
    }
    if (summary.focusSessions > 0) add("专注 ${compactDuration(summary.focusSeconds)}")
}.joinToString(" · ")

/** 目标只在设置过时出现；没设置就不猜用户想要多少。 */
private fun dayGoalLines(summary: DaySummary, settings: UserSettings): List<String> = buildList {
    settings.dailyQuestionGoal?.takeIf { it > 0 }?.let { goal ->
        add(if (summary.questions >= goal) "题量目标 $goal 题：已达成" else "题量目标 $goal 题：还差 ${goal - summary.questions} 题")
    }
    settings.dailyMinuteGoal?.takeIf { it > 0 }?.let { goal ->
        val minutes = summary.timedSeconds / 60
        add(
            if (minutes >= goal.toLong()) "时长目标 $goal 分钟：已达成"
            else "时长目标 $goal 分钟：还差 ${goal - minutes.toInt()} 分钟",
        )
    }
}

/** 发现层：最近 7 天与前 7 天比，只列真正涨了或掉了的类别，两边各至少 8 题才计算。 */


/**
 * 关键词云：字号按出现次数开方缩放，点击任一关键词查看关联记录。
 * 布局是纯计算（[TagCloudLayout]），放不下的词改用下方标签展示，不会悄悄丢掉。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TagCloudCard(state: AppState) {
    val tags = state.tagStats.take(24)
    var detail by remember { mutableStateOf<TagStat?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text("错题关键词", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (tags.isNotEmpty()) {
                Text("点击关键词看关联记录", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // v5：计分 / 复做的关键词单独成组，不混进客观题词云，也不参与主正确率。
        if (state.otherTagStats.isNotEmpty()) {
            Text(
                "计分 / 复做关键词（单独成组）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.otherTagStats.take(16).forEach { stat ->
                    SuggestionChip(
                        onClick = { detail = stat },
                        label = { Text("${stat.tag} · ${stat.sessionCount}", style = MaterialTheme.typography.labelSmall) },
                        shape = RoundedCornerShape(2.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            Text(
                "这些词只描述主观题与复做的错因；它们不与客观题词云合并，也不推断因果。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
        }
        if (tags.isEmpty()) {
            Text(
                "记录错题时顺手写下一个关键词（如「属性规律」），这里会按出现次数长出词云。越常出现的错因，字号越大。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        val density = LocalDensity.current
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val widthPx = with(density) { maxWidth.toPx() }
            val minPx = with(density) { 13.sp.toPx() }
            val maxPx = with(density) { 28.sp.toPx() }
            val sizes = TagCloudLayout.scale(tags.map { it.sessionCount }, minPx, maxPx)
            val items = tags.mapIndexed { index, stat -> TagCloudLayout.Item(stat.tag, sizes[index]) }
            val placements = TagCloudLayout.place(widthPx, with(density) { 190.dp.toPx() }, items)
            val placed = placements.map { it.index }.toSet()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().height(190.dp)) {
                    placements.forEach { placement ->
                        val stat = tags[placement.index]
                        val sizePx = items[placement.index].sizePx
                        Text(
                            stat.tag,
                            fontSize = with(density) { sizePx.toSp() },
                            fontWeight = if (sizePx >= maxPx * 0.7f) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (placement.index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.offset { IntOffset(placement.left.roundToInt(), placement.top.roundToInt()) }
                                .clickable { detail = stat }
                                .padding(2.dp),
                        )
                    }
                }
                val overflow = tags.filterIndexed { index, _ -> index !in placed }
                if (overflow.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        overflow.forEach { stat ->
                            SuggestionChip(
                                onClick = { detail = stat },
                                label = { Text(stat.tag, style = MaterialTheme.typography.labelMedium) },
                                shape = RoundedCornerShape(2.dp),
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }
            }
        }
        Text(
            "共 ${state.tagStats.size} 个关键词，按出现次数显示前 ${tags.size} 个。关键词由你填写，不参与正确率计算。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    detail?.let { stat -> TagDetailSheet(stat, state) { detail = null } }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TagDetailSheet(stat: TagStat, state: AppState, onDismiss: () -> Unit) {
    val sessions = state.sessions.filter { it.id in stat.sessionIds }.sortedByDescending { it.startAt }
    val byCategory = sessions.groupBy { it.categoryId }
        .map { (categoryId, rows) ->
            categoryId to (rows.size to DayStats.summarize(rows, state.today, state.settings.dateAttribution))
        }
        .sortedByDescending { it.second.second.questions }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stat.tag, style = MaterialTheme.typography.headlineSmall)
            Text(
                "${stat.sessionCount} 次 · ${stat.questionCount} 题 · 错 ${stat.wrongCount} 题 · 正确率 ${accuracyText(stat.accuracy)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                buildString {
                    append(if (stat.netSeconds > 0) "累计计时 ${compactDuration(stat.netSeconds)}" else "未计时")
                    append(" · 最近 ")
                    append(if (stat.lastUsed == LocalDate.MIN) "—" else "${stat.lastUsed.monthValue} 月 ${stat.lastUsed.dayOfMonth} 日")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "关键词是自填的，命名越稳定，越能看出反复出现的错因。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (byCategory.size > 1) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
                Text("出现在这些类别", style = MaterialTheme.typography.labelLarge)
                byCategory.forEach { (categoryId, pair) ->
                    val name = state.categories.find { it.id == categoryId }?.name ?: "已归档类别"
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(
                            "${pair.first} 次 · ${pair.second.questions} 题 · ${accuracyText(pair.second.accuracy)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("关联记录", style = MaterialTheme.typography.labelLarge)
            sessions.take(8).forEach { session -> SessionCard(session, state) }
            if (sessions.size > 8) {
                Text(
                    "仅显示最近 8 组，其余可在记录页按日期回看。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun confidenceLabel(level: ConfidenceLevel) = when (level) {
    ConfidenceLevel.TRUSTED -> "可信"
    ConfidenceLevel.REFERENCE -> "参考"
    ConfidenceLevel.INSUFFICIENT -> "样本不足"
}


@OptIn(ExperimentalLayoutApi::class)
/**
 * 看板：五章一条线。
 *
 * v0.11 之前每次加功能都在末尾追加一张卡片，最后攒到 18 个同级盒子、5 屏滚动，
 * 「哪块最重要」这个信息被抹平了。现在固定为五章：节律 → 表现 → 投入 → 值得注意 → 细节。
 * 每章是一个列表项（章内 14dp、章间 28dp），所以滚动时的分组是能被看见的。
 */
@Composable
private fun BoardScreen(
    state: AppState,
    onCoverage: () -> Unit,
    onPrescription: (Long) -> Unit,
    generatePrescriptions: () -> Unit,
    openBags: () -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            item { PageHeading("不只看数字，更看下一步", "学习看板") }
            // 一 · 节律：月历是「哪天断了」的第一入口，紧贴标题，不被任何摘要压在下面。
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ChapterHead("01", "节律", "一天四根条；先看哪天断了，再看断在什么上")
                    MonthHeatmap(state)
                }
            }
            item { PerformanceChapter(state) }
            item { InvestmentChapter(state) }
            item { AttentionChapter(state, openBags, onPrescription, generatePrescriptions) }
            item { DetailChapter(state, onCoverage) }
            item { StatisticsNote() }
        }
        BackToTop(
            visible = listState.firstVisibleItemIndex > 2,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp),
        ) {
            scope.launch { listState.animateScrollToItem(0) }
        }
    }
}

/**
 * 统计口径：原来散落在六个卡片里的免责声明。
 *
 * 规则本身必须留（它们解释了口径），但六段并列的小字会让页面读起来像说明书，
 * 而且每段都在重复同一句话——「这三样不能加在一起」。收成一处，随时可查。
 */
@Composable
private fun StatisticsNote() {
    Disclosure(
        "统计口径与边界",
        "· 正确率、得分率、复做通过是三条量纲不同的轨，只并列，不合并成综合分，也不做能力分。\n" +
            "· 同类练习满 30 题、3 次才用于可靠判断；未计时的记录不参与速度比较。\n" +
            "· 月历的条长与着色只表示当天做了多少，不表示掌握程度。\n" +
            "· 遗忘估计与投入回报尚未开放；训练处方只按当前样本给，不预测长期收益。\n" +
            "· 记录只保存在这台设备，没有网络权限，也不采集设备信息。",
    )
}

@Composable
internal fun rememberCoverage(state: AppState): CoverageMap {
    val attribution = state.settings.dateAttribution
    return remember(state.sessions, state.categories, state.subjects, state.today, attribution) {
        CoverageMap.calculate(state.sessions, state.categories, state.subjects, state.today, attribution)
    }
}


/** 看板上的处方入口：进行中的排在前面，点开才展示依据、时间线与验证。 */
@Composable
internal fun PrescriptionSection(
    state: AppState,
    open: (Long) -> Unit,
    generate: () -> Unit,
) {
    val active = state.prescriptions.filter { it.isActive }
        .sortedWith(compareBy<Prescription> { if (it.status == PrescriptionStatus.STARTED) 0 else 1 }.thenBy { it.createdAt })
    val recent = state.prescriptions.filter { !it.isActive }.take(3)
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("训练处方", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            Text("按今天的剩余预算", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (active.isEmpty()) {
            Text("今天还没有安排", style = MaterialTheme.typography.titleMedium)
            Text(
                "按剩余题量或时间排一组，一天最多两条；没有目标时只给一条自愿行动，不自动排满。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = generate, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                Text("生成今日处方", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            active.forEachIndexed { index, prescription ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
                PrescriptionRow(state, prescription, open)
            }
            if (active.size < PrescriptionPlanner.MAX_PER_DAY) {
                TextButton(onClick = generate, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                    Text("再排一条", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (recent.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
            Text("最近", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            recent.forEach { prescription -> PrescriptionRow(state, prescription, open, compact = true) }
        }
    }
}

@Composable
private fun PrescriptionRow(
    state: AppState,
    prescription: Prescription,
    open: (Long) -> Unit,
    compact: Boolean = false,
) {
    val categoryName = state.categories.find { it.id == prescription.categoryId }?.name ?: "已归档类别"
    Column(
        Modifier.fillMaxWidth()
            .clickable { open(prescription.id) }
            .padding(vertical = if (compact) 4.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$categoryName · ${prescription.type.label}",
                style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
            )
            Text(
                prescriptionStatusLabel(prescription.status),
                style = MaterialTheme.typography.labelSmall,
                color = prescriptionStatusTone(prescription.status),
            )
        }
        Text(
            prescriptionTarget(prescription),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!compact) Text(prescription.reason, style = MaterialTheme.typography.bodySmall)
    }
}

/** 处方详情：生成 → 开始 → 完成 → 验证四态；验证只在完成后有样本时展示。 */
@Composable
private fun PrescriptionDetail(
    state: AppState,
    prescription: Prescription?,
    onStart: (Prescription, () -> Unit) -> Unit,
    onComplete: (Prescription) -> Unit,
    onSkip: (Prescription) -> Unit,
    openTimer: () -> Unit,
    openBag: (Long) -> Unit,
) {
    if (prescription == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("这条处方已不在当前考试里。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val categoryName = state.categories.find { it.id == prescription.categoryId }?.name ?: "已归档类别"
    val evaluation = remember(prescription, state.sessions) {
        val completedAt = prescription.completedAt
        if (prescription.status == PrescriptionStatus.DONE && completedAt != null) {
            PrescriptionEvaluator.evaluate(
                sessions = state.sessions,
                categoryId = prescription.categoryId,
                startedAt = prescription.startedAt ?: completedAt,
                completedAt = completedAt,
            )
        } else {
            null
        }
    }
    LazyColumn(
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            PageHeading(
                eyebrow = "${prescription.type.label} · ${prescriptionStatusLabel(prescription.status)}",
                title = categoryName,
            )
        }
        // D26：复习 / 攻坚处方优先指向有未消化错题的袋，这里给出一键回看入口。
        prescription.batchId?.let { batchId ->
            item {
                val batch = state.wrongBatches.find { it.id == batchId }
                val remaining = batch?.let {
                    RedoLedger.ledger(
                        it,
                        state.reviewAttempts.filter { attempt -> attempt.batchId == batchId },
                        state.today,
                    ).remaining
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "这条处方指向一组还没消化完的错题。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { openBag(batchId) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(2.dp),
                    ) { Text(if (remaining != null) "查看关联错题袋（还剩 $remaining 题）" else "查看关联错题袋") }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("目标", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(prescriptionTarget(prescription), style = MaterialTheme.typography.headlineSmall)
                Text(prescription.reason, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("为什么是它", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    prescription.attribution,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { PrescriptionTimeline(prescription) }
        if (prescription.isActive) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { onStart(prescription) { openTimer() } },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(2.dp),
                    ) {
                        Text(
                            if (prescription.status == PrescriptionStatus.STARTED) "继续计时" else "开始计时",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { onComplete(prescription) }) { Text("标记完成") }
                        TextButton(onClick = { onSkip(prescription) }) { Text("跳过") }
                    }
                }
            }
        }
        if (evaluation != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("验证", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        verdictLabel(evaluation.verdict),
                        style = MaterialTheme.typography.headlineSmall,
                        color = verdictTone(evaluation.verdict),
                    )
                    if (evaluation.beforeAccuracy != null && evaluation.afterAccuracy != null) {
                        Text(
                            "正确率 ${prescriptionPercent(evaluation.beforeAccuracy)} → ${prescriptionPercent(evaluation.afterAccuracy)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (evaluation.beforeSecPerQ != null && evaluation.afterSecPerQ != null) {
                        Text(
                            "每题用时 ${prescriptionSeconds(evaluation.beforeSecPerQ)} 秒 → ${prescriptionSeconds(evaluation.afterSecPerQ)} 秒",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        evaluation.note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.prescriptionResults.containsKey(prescription.id)) {
                        Text(
                            "验证结果已随本机记录保存。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (!prescription.isActive) {
            item {
                NoteCard(
                    "下一步",
                    when (prescription.status) {
                        PrescriptionStatus.EXPIRED -> "这条已过期，未执行的练习不计入完成；需要时重新生成一条。"
                        PrescriptionStatus.SKIPPED -> "跳过不影响已有记录；需要时重新生成一条。"
                        PrescriptionStatus.INVALIDATED ->
                            prescription.invalidatedReason ?: "关联记录已变化，这条处方的结论不再有效。"
                        else -> "验证需要前后各 3 次、各 32 题；完成后的前 3 次记录用于后测，执行期间的记录不计入。"
                    },
                )
            }
        }
        item {
            NoteCard(
                "口径",
                "处方只按当前样本与当天剩余预算安排；样本不足时如实显示不足，不提前下结论。",
            )
        }
    }
}

@Composable
private fun PrescriptionTimeline(prescription: Prescription) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PrescriptionTimeRow("生成", prescription.createdAt)
        prescription.startedAt?.let { PrescriptionTimeRow("开始", it) }
        prescription.completedAt?.let { PrescriptionTimeRow("完成", it) }
        if (prescription.status == PrescriptionStatus.EXPIRED) PrescriptionTimeRow("截止", prescription.dueAt)
    }
}

@Composable
private fun PrescriptionTimeRow(label: String, at: Long) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(prescriptionTime(at), style = MaterialTheme.typography.bodySmall)
    }
}

private fun prescriptionTime(at: Long): String = Instant.ofEpochMilli(at)
    .atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA))

private fun prescriptionTarget(prescription: Prescription): String = buildString {
    append("${prescription.type.sets} 组 × ${prescription.type.perSet} 题")
    append(
        prescription.targetMinutes?.let { " · 约 $it 分钟" } ?: " · 不限时",
    )
    prescription.targetAccuracy?.let { append(" · 当前正确率 ${prescriptionPercent(it)}") }
}

private fun prescriptionPercent(value: Double): String = String.format(Locale.CHINA, "%.1f%%", value * 100)

private fun prescriptionSeconds(value: Double): String = String.format(Locale.CHINA, "%.1f", value)

private fun prescriptionStatusLabel(status: PrescriptionStatus): String = when (status) {
    PrescriptionStatus.PENDING -> "待开始"
    PrescriptionStatus.STARTED -> "进行中"
    PrescriptionStatus.DONE -> "已完成"
    PrescriptionStatus.EXPIRED -> "已过期"
    PrescriptionStatus.SKIPPED -> "已跳过"
    PrescriptionStatus.INVALIDATED -> "已作废"
}

@Composable
private fun prescriptionStatusTone(status: PrescriptionStatus) = when (status) {
    PrescriptionStatus.PENDING, PrescriptionStatus.STARTED -> MaterialTheme.colorScheme.secondary
    PrescriptionStatus.DONE -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun verdictLabel(verdict: Verdict): String = when (verdict) {
    Verdict.EFFECTIVE -> "有效"
    Verdict.NEUTRAL -> "无明显变化"
    Verdict.INEFFECTIVE -> "未见效果"
    Verdict.INSUFFICIENT -> "样本不足"
}

@Composable
private fun verdictTone(verdict: Verdict) = when (verdict) {
    Verdict.EFFECTIVE -> MaterialTheme.colorScheme.primary
    Verdict.INEFFECTIVE -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onBackground
}

/** 覆盖屏：科目行优先；类别层只展开练过的，未练类别只报数量，不逐条制造清单压力。 */
@Composable
private fun CoverageScreen(state: AppState, generatePrescriptions: () -> Unit) {
    val coverage = rememberCoverage(state)
    var expandedId by rememberSaveable { mutableLongStateOf(-1L) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item { PageHeading("先确认练过哪里，再谈练得怎样", "覆盖地图") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(coverage.gapSummary(), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "覆盖只回答“有没有开始”，不是“掌握到什么程度”。没记录过的类别说明还缺样本，不能据此判定薄弱。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { Text("按科目", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (coverage.subjects.isEmpty()) {
                item { NoteCard("还没有可统计的科目", "新建科目和类别后，这张地图会随记录一起生长。") }
            }
            items(coverage.subjects, key = { it.subjectId }) { subject ->
                SubjectCoverageRow(
                    state,
                    subject,
                    expanded = expandedId == subject.subjectId,
                ) {
                    expandedId = if (expandedId == subject.subjectId) -1L else subject.subjectId
                }
            }
            item { NoteCard("覆盖不等于掌握", "某个类别没有记录，只说明还没开始采样；把它当作摸底对象，而不是短板结论。") }
            item {
                Text(
                    if (coverage.hasAnyPractice) {
                        "先给没有记录的类别各做 10 题摸底，覆盖会自己长出来。"
                    } else {
                        "先做一次 10 题摸底，这张地图就会开始生长。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                TextButton(onClick = generatePrescriptions, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
                    Text("按今天的预算安排练习", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun SubjectCoverageRow(
    state: AppState,
    subject: SubjectCoverage,
    expanded: Boolean,
    toggle: () -> Unit,
) {
    val name = state.subjects.find { it.id == subject.subjectId }?.name ?: "已归档科目"
    val expandable = subject.categories.isNotEmpty()
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
        Row(
            Modifier.fillMaxWidth()
                .clickable(enabled = expandable, onClick = toggle)
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        coverageStatusLabel(subject.status, subject.isStale),
                        style = MaterialTheme.typography.labelSmall,
                        color = coverageTone(subject.status, subject.isStale),
                    )
                }
                Text(
                    coverageDetail(subject.questionCount, subject.sessionCount, subject.daysSinceLastPractice, subject.untouchedCategories),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (expandable) {
                    Text(
                        if (expanded) "收起类别" else "${subject.categories.size} 个类别 · 展开",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (expanded) {
            subject.categories.forEach { category -> CategoryCoverageRow(state, category) }
        }
    }
}

@Composable
private fun CategoryCoverageRow(state: AppState, category: CategoryCoverage) {
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.categories.find { it.id == category.categoryId }?.name ?: "已归档类别",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                coverageStatusLabel(category.status, category.isStale),
                style = MaterialTheme.typography.labelSmall,
                color = coverageTone(category.status, category.isStale),
            )
        }
        Text(
            coverageDetail(category.questionCount, category.sessionCount, category.daysSinceLastPractice),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun coverageStatusLabel(status: CoverageStatus, stale: Boolean): String = when {
    status == CoverageStatus.UNTOUCHED -> "未开始"
    stale -> "久未练"
    status == CoverageStatus.TRUSTED -> "可信"
    else -> "采样中"
}

@Composable
private fun coverageTone(status: CoverageStatus, stale: Boolean) = when {
    status == CoverageStatus.UNTOUCHED -> MaterialTheme.colorScheme.onSurfaceVariant
    stale -> MaterialTheme.colorScheme.secondary
    status == CoverageStatus.TRUSTED -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun coverageDetail(
    questionCount: Int,
    sessionCount: Int,
    daysSinceLastPractice: Long?,
    untouchedCategories: Int = 0,
): String = buildString {
    if (sessionCount == 0) {
        append("0 题")
    } else {
        append("$questionCount 题 · $sessionCount 次 · ")
        append(confidenceLabel(Thresholds.confidence(questionCount, sessionCount)))
        append(" · ")
        append(lastPracticedText(daysSinceLastPractice))
    }
    if (untouchedCategories > 0) {
        if (isNotEmpty()) append(" · ")
        append("$untouchedCategories 个类别未开始")
    }
}

private fun lastPracticedText(days: Long?): String = when {
    days == null -> "还没练过"
    days <= 0L -> "今天练过"
    days == 1L -> "昨天练过"
    else -> "最近 $days 天前"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionCard(session: Session, state: AppState, edit: (() -> Unit)? = null, delete: (() -> Unit)? = null) {
    val tags = TagRules.parse(session.wrongTags)
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
        Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    state.subjects.find { it.id == session.subjectId }?.name.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    sessionTimeRange(session),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(state.categories.find { it.id == session.categoryId }?.name ?: "已归档类别", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (session.isFocus) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            compactDuration((session.durationSec ?: 0).toLong()),
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    Text(
                        "专注 · 只计投入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${session.totalCount}", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
                        Text(" 题", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp))
                    }
                    Text(
                        sessionResultLine(
                            session.mode, session.kind, session.totalCount, session.correctCount,
                            session.scoreTenths, session.maxScoreTenths, session.durationSec,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        session.durationSec?.let { "${it / 60}:${String.format(Locale.CHINA, "%02d", it % 60)}" } ?: "未计时",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
            if (tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { tag -> TagPill(tag) }
                }
            }
            if (edit != null || delete != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (edit != null) TextButton(onClick = edit, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("编辑", style = MaterialTheme.typography.bodySmall)
                }
                if (delete != null) TextButton(onClick = delete, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("删除", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TagPill(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** 错题关键词选填项：已选的关键词可点掉，最近用过的可一键复用，输入支持逗号/竖线一次填多个。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WrongTagField(value: String?, onChange: (String?) -> Unit, recent: List<String>, modifier: Modifier = Modifier) {
    var draft by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    val selected = TagRules.parse(value)
    val suggestions = recent.filterNot { it in selected }.take(6)
    fun add(raw: String) {
        if (raw.isBlank()) return
        val result = TagRules.append(value, raw)
        hint = result.error
        if (result.error == null) {
            onChange(TagRules.normalize(result.value))
            draft = ""
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("错题关键词（选填）", style = MaterialTheme.typography.titleMedium)
        Text(
            "写下错因或题型，比如「属性规律」「增长率-基期」。看板会把它们聚成词云，方便复盘。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (selected.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                selected.forEach { tag ->
                    InputChip(
                        selected = true,
                        onClick = { onChange(TagRules.remove(value, tag)); hint = null },
                        label = { Text(tag, style = MaterialTheme.typography.labelMedium) },
                        trailingIcon = { Text("×", style = MaterialTheme.typography.titleMedium) },
                        shape = RoundedCornerShape(2.dp),
                        modifier = Modifier.heightIn(min = 40.dp),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                draft,
                { draft = it; hint = null },
                label = { Text("添加关键词") },
                placeholder = { Text("例如 属性规律") },
                singleLine = true,
                isError = hint != null,
                supportingText = {
                    Text(hint ?: "最多 ${TagRules.MAX_TAGS} 个，每个 ${TagRules.MAX_LENGTH} 字以内。")
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add(draft) }),
                modifier = Modifier.weight(1f).testTag("wrong-tag-input").then(modifier),
            )
            TextButton(onClick = { add(draft) }, enabled = draft.isNotBlank()) { Text("添加") }
        }
        if (suggestions.isNotEmpty()) {
            Text("最近用过", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                suggestions.forEach { tag ->
                    SuggestionChip(
                        onClick = { add(tag) },
                        label = { Text(tag, style = MaterialTheme.typography.labelMedium) },
                        shape = RoundedCornerShape(2.dp),
                        modifier = Modifier.heightIn(min = 40.dp),
                    )
                }
            }
        }
    }
}


@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun RecordsScreen(
    state: AppState,
    model: MainViewModel,
    tab: String,
    onTab: (String) -> Unit,
    start: () -> Unit,
    add: () -> Unit,
    edit: (Session) -> Unit,
    delete: (Session) -> Unit,
    openBag: (Long) -> Unit,
) {
    var bagFilterName by rememberSaveable { mutableStateOf(BagFilter.PENDING.name) }
    val bagFilter = BagFilter.valueOf(bagFilterName)
    var filterCategory by rememberSaveable { mutableLongStateOf(0L) }
    // v0.11：记录页按三种记录方式筛选；null 表示「全部」。
    var filterModeKey by rememberSaveable { mutableStateOf<String?>(null) }
    var order by rememberSaveable { mutableStateOf(RecordOrder.NEWEST) }
    var shownDays by rememberSaveable { mutableIntStateOf(RecordPaging.PAGE_DAYS) }
    var shownRows by rememberSaveable { mutableIntStateOf(FLAT_PAGE_ROWS) }
    var sortMenu by remember { mutableStateOf(false) }
    // v0.15：筛选收进底部面板。默认视图保持「全部记录 + 按日期分组」，一眼能扫到底。
    var filterSheet by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val activeFilters = (if (filterCategory == 0L) 0 else 1) + (if (filterModeKey == null) 0 else 1)
    val usedCategories = state.sessions.map { it.categoryId }.toSet()
        .mapNotNull { id -> state.categories.find { it.id == id } }
        .sortedBy { it.name }
    val scoped = state.sessions.filter { filterCategory == 0L || it.categoryId == filterCategory }
    val filtered = scoped.filter { filterModeKey == null || it.mode.name == filterModeKey }
    val sorted = SessionOrdering.sort(filtered, order)
    val byDate = sorted.groupBy { sessionDate(it, state.settings.dateAttribution) }
        .toSortedMap(if (order == RecordOrder.NEWEST) compareByDescending { it } else compareBy { it })
    val totalDays = byDate.size
    val shownDayCount = shownDays.coerceIn(0, totalDays)
    val remainingDays = totalDays - shownDayCount
    val shownRowCount = shownRows.coerceIn(0, sorted.size)
    val remainingRows = sorted.size - shownRowCount

    LaunchedEffect(filterCategory, order, filterModeKey) {
        shownDays = RecordPaging.PAGE_DAYS
        shownRows = FLAT_PAGE_ROWS
        listState.scrollToItem(0)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.testTag("records-list"),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                PageHeading("每一次认真，都值得留下", "练习记录") {
                    Box(
                        Modifier.size(40.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
                            .clickable(onClick = start),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Add, "开始计时练习", Modifier.size(18.dp)) }
                }
                Spacer(Modifier.height(10.dp))
                UnderlineTabs(listOf("记录", "错题复做"), if (tab == "records") 0 else 1) { index ->
                    onTab(if (index == 0) "records" else "bags")
                }
                // 三排筛选原来把列表压到 300dp 以下；现在只留一行「条数 · 筛选 · 排序」。
                Spacer(Modifier.height(10.dp))
                if (tab == "records") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            // 只在左侧报条数；「筛选 N」在右侧已经有了，这里再写一次「已筛选」是同一句话说两遍。
                            if (activeFilters == 0) "${state.sessions.size} 条记录" else "${filtered.size} 条",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (activeFilters == 0) "筛选" else "筛选 $activeFilters",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (activeFilters == 0) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.secondary
                            },
                            modifier = Modifier.testTag("records-filter").minimumInteractiveComponentSize()
                                .clickable { filterSheet = true }.padding(vertical = 8.dp, horizontal = 4.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Box {
                            Text(
                                "${order.label} ▾",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.testTag("records-sort").minimumInteractiveComponentSize()
                                    .clickable { sortMenu = true }.padding(vertical = 8.dp, horizontal = 4.dp),
                            )
                            DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                                RecordOrder.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                option.label,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (option == order) {
                                                    MaterialTheme.colorScheme.secondary
                                                } else {
                                                    MaterialTheme.colorScheme.onBackground
                                                },
                                            )
                                        },
                                        onClick = { order = option; sortMenu = false },
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
            if (tab == "bags") {
                wrongBatchesSection(state, model, bagFilter, { bagFilterName = it.name }, openBag)
            } else {
            if (sorted.isEmpty()) {
                val hasFilter = filterCategory != 0L || filterModeKey != null
                item {
                    Spacer(Modifier.height(16.dp))
                    NoteCard(
                        when {
                            hasFilter && filterModeKey == RecordMode.FOCUS.name -> "这段时间还没有专注记录"
                            hasFilter && filterCategory != 0L -> "这个类别还没有记录"
                            hasFilter -> "这个筛选下还没有记录"
                            else -> "从第一条记录开始"
                        },
                        if (hasFilter) "换个条件，或清除筛选看全部。"
                        else "选好科目和类别，按下开始计时，结束后填写题量；也可以事后补记。",
                    )
                    Spacer(Modifier.height(12.dp))
                    if (!hasFilter) {
                        Button(onClick = start, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(2.dp)) { Text("开始计时") }
                        TextButton(onClick = add, modifier = Modifier.fillMaxWidth()) { Text("补记一次", style = MaterialTheme.typography.bodyMedium) }
                    } else {
                        TextButton(
                            onClick = { filterCategory = 0L; filterModeKey = null },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("清除筛选", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            if (order.groupsByDate) {
                byDate.entries.take(shownDayCount).forEach { (date, sessions) ->
                    val daySummary = DayStats.summarize(sessions, date, state.settings.dateAttribution)
                    stickyHeader(key = "h-$date") {
                        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                        Row(
                            Modifier.fillMaxWidth()
                                .padding(top = 16.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                date.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                            Text(
                                buildString {
                                    append("${daySummary.sessionCount} 组 · ${daySummary.questions} 题")
                                    append(" · 正确率 ${accuracyText(daySummary.accuracy)}")
                                    if (daySummary.timedSeconds > 0) append(" · ${compactDuration(daySummary.timedSeconds)}")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
                        }
                    }
                    items(sessions, key = { it.id }) { session -> SessionCard(session, state, { edit(session) }, { delete(session) }) }
                }
            } else {
                items(sorted.take(shownRowCount), key = { it.id }) { session ->
                    SessionCard(session, state, { edit(session) }, { delete(session) })
                }
            }
            item(key = "footer") {
                Spacer(Modifier.height(10.dp))
                val remaining = if (order.groupsByDate) remainingDays else remainingRows
                if (remaining > 0) {
                    val pageSize = if (order.groupsByDate) RecordPaging.PAGE_DAYS else FLAT_PAGE_ROWS
                    Text(
                        "加载更早的 ${minOf(pageSize, remaining)} ${if (order.groupsByDate) "天" else "条"}（还剩 $remaining）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
                            .clickable {
                                if (order.groupsByDate) shownDays += RecordPaging.PAGE_DAYS else shownRows += FLAT_PAGE_ROWS
                            }
                            .padding(vertical = 14.dp),
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text(
                        if (order.groupsByDate) "已显示全部 ${totalDays} 天" else "已显示全部 ${sorted.size} 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            }
        }

        BackToTop(
            visible = listState.firstVisibleItemIndex > 3,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp),
        ) {
            scope.launch { listState.animateScrollToItem(0) }
        }
        if (filterSheet) {
            RecordsFilterSheet(
                categories = usedCategories,
                categoryCounts = remember(usedCategories, state.sessions) {
                    usedCategories.associate { category -> category.id to state.sessions.count { it.categoryId == category.id } }
                },
                scopedSessions = scoped,
                totalCount = state.sessions.size,
                resultCount = filtered.size,
                category = filterCategory,
                mode = filterModeKey,
                onCategory = { filterCategory = it },
                onMode = { filterModeKey = it },
                onClear = { filterCategory = 0L; filterModeKey = null },
                onDismiss = { filterSheet = false },
            )
        }
    }
}

/**
 * 筛选面板：类别与记录方式都放在一次点击之后。
 *
 * 这两排 chips 原本常驻在列表上方，占掉近 120dp，而且和「记录 / 错题复做」的分段用同一种形状，
 * 读起来分不清层级。收进面板后，列表上方只留「条数 · 筛选 · 排序」一行；
 * 启用了几项会写成「筛选 2」，不用展开也知道当前不是全部。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RecordsFilterSheet(
    categories: List<Category>,
    categoryCounts: Map<Long, Int>,
    scopedSessions: List<Session>,
    totalCount: Int,
    resultCount: Int,
    category: Long,
    mode: String?,
    onCategory: (Long) -> Unit,
    onMode: (String?) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 默认的半展开只露出内容上半截：类别有 106 项时，固定底栏也会被切在折叠线以下。
    // 这个面板本来就是「挑一项再走」，直接全展开。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("筛选", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    Text(
                        "清除",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.heightIn(min = 48.dp).clickable(onClick = onClear).padding(top = 14.dp),
                    )
                }
                Text("记录方式", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == null,
                        onClick = { onMode(null) },
                        label = { Text("全部 $totalCount", style = MaterialTheme.typography.bodySmall) },
                        shape = RoundedCornerShape(2.dp),
                        colors = inkChipColors(),
                        modifier = Modifier.heightIn(min = 44.dp),
                    )
                    listOf(
                        RecordMode.COUNT to "正确率",
                        RecordMode.SCORE to "得分",
                        RecordMode.FOCUS to "专注",
                    ).forEach { (option, label) ->
                        FilterChip(
                            selected = mode == option.name,
                            onClick = { onMode(if (mode == option.name) null else option.name) },
                            label = {
                                Text(
                                    "$label ${scopedSessions.count { it.mode == option }}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            shape = RoundedCornerShape(2.dp),
                            colors = inkChipColors(),
                            modifier = Modifier.heightIn(min = 44.dp),
                        )
                    }
                }
                if (categories.isNotEmpty()) {
                    Text("类别", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = category == 0L,
                            onClick = { onCategory(0L) },
                            label = { Text("全部类别", style = MaterialTheme.typography.bodySmall) },
                            shape = RoundedCornerShape(2.dp),
                            colors = inkChipColors(),
                            modifier = Modifier.heightIn(min = 44.dp),
                        )
                        categories.forEach { item ->
                            FilterChip(
                                selected = category == item.id,
                                onClick = { onCategory(if (category == item.id) 0L else item.id) },
                                label = {
                                    Text(
                                        "${item.name} ${categoryCounts[item.id] ?: 0}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                                shape = RoundedCornerShape(2.dp),
                                colors = inkChipColors(),
                                modifier = Modifier.heightIn(min = 44.dp),
                            )
                        }
                    }
                }
            }
            // 按钮固定在面板底部。106 个类别时它会跟着内容滚出两屏，
            // 打开面板看不到「会筛出几条」，等于没有这个按钮。
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 20.dp),
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(2.dp),
                ) { Text("看这 $resultCount 条", style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}

/** 列表回到顶部：只在滚过一屏后出现，点击滚回首项并清掉下拉状态。 */
@Composable
private fun BackToTop(visible: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("↑", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary)
            Text("顶部", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

private const val FLAT_PAGE_ROWS = 50

private fun timeOfDay(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA))

private fun dateLabel(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA))

/**
 * 记录卡上的真实时段。补记只有日期锚点，没有真实起止，不能伪装成时刻；
 * 跨零点按设计书展示 24:03 这种写法，避免被读成同一天凌晨。
 */
private fun sessionTimeRange(session: Session): String {
    val end = session.endAt
    if (end == null || end <= session.startAt) {
        val untimed = session.durationSec == null
        return when {
            session.source == SessionSource.MANUAL && untimed -> "补记 · 未计时"
            session.source == SessionSource.MANUAL -> "补记 · 未记录时段"
            untimed -> "未计时"
            else -> "未记录时段"
        }
    }
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(session.startAt).atZone(zone)
    val endZ = Instant.ofEpochMilli(end).atZone(zone)
    val dayGap = ChronoUnit.DAYS.between(start.toLocalDate(), endZ.toLocalDate()).toInt()
    val endLabel = when {
        dayGap == 0 -> endZ.format(DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA))
        dayGap == 1 -> String.format(Locale.CHINA, "%02d:%02d", endZ.hour + 24, endZ.minute)
        else -> endZ.format(DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA))
    }
    val pause = if (session.pauseCount > 0) " · 暂停 ${session.pauseCount} 次" else ""
    return "${start.format(DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA))} – $endLabel$pause"
}

private fun formatElapsed(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = seconds % 60
    return if (hours > 0) String.format(Locale.CHINA, "%d:%02d:%02d", hours, minutes, rest)
    else String.format(Locale.CHINA, "%02d:%02d", minutes, rest)
}

private fun accuracyText(accuracy: Double?): String =
    accuracy?.let { String.format(Locale.CHINA, "%.1f%%", it * 100) } ?: "—"

/** 日历与发现里的口语化时长：不显示秒，除非不足一分钟。 */
internal fun compactDuration(seconds: Long): String = when {
    seconds <= 0 -> "0 分钟"
    seconds < 60 -> "${seconds} 秒"
    seconds < 3600 -> "${seconds / 60} 分钟"
    else -> {
        val minutes = (seconds % 3600) / 60
        if (minutes == 0L) "${seconds / 3600} 小时" else "${seconds / 3600} 小时 $minutes 分"
    }
}

/** 番茄钟档位：null 表示自由计时。倒计时只影响结算时机，不影响记录的真实起止时间。 */
private data class TimerModeOption(val label: String, val targetSec: Int?, val hint: String)

private val TIMER_MODES = listOf(
    TimerModeOption("自由计时", null, "不计上限，按实际用时结算；适合成套练习。"),
    TimerModeOption("25 分钟", 25 * 60, "一个番茄钟；到点自动结算，中途可以暂停。"),
    TimerModeOption("45 分钟", 45 * 60, "一节课的长度；到点自动结算。"),
    TimerModeOption("60 分钟", 60 * 60, "整块时间；到点自动结算。"),
)

private fun defaultCount(sessions: List<Session>, categoryId: Long): String {
    val history = sessions.filter { it.categoryId == categoryId }
    val frequencies = history.groupingBy { it.totalCount }.eachCount()
    return (history.maxByOrNull { frequencies[it.totalCount] ?: 0 }?.totalCount ?: 16).toString()
}

@Composable
private fun TimerScreen(
    state: AppState,
    running: TimerStart?,
    finished: FinishedTimer?,
    busy: Boolean,
    bootCount: Int,
    start: (Long, Long, Int?) -> Unit,
    startFocus: (Long, Long, Int?) -> Unit,
    initialFocus: Boolean,
    end: () -> Unit,
    discard: () -> Unit,
    save: (Session) -> Unit,
    addManual: () -> Unit,
    pause: () -> Unit,
    resume: () -> Unit,
    createSubject: (String, (Long) -> Unit) -> Unit,
    createCategory: (Long, String, (Long) -> Unit) -> Unit,
    rememberCustomMinutes: (Int) -> Unit,
    onNotificationPermissionDenied: () -> Unit,
    model: MainViewModel,
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            // 计时可能在授权前就已开始，前台服务的首条通知会被系统丢弃；授权后补发一次。
            TimerService.refresh(context)
        } else {
            onNotificationPermissionDenied()
        }
    }
    var focusMode by rememberSaveable { mutableStateOf(initialFocus) }
    // 权限只影响锁屏与通知栏的进度显示，计时本身无论授权与否都照常开始。
    val requestStart: (Long, Long, Int?) -> Unit = { subjectId, categoryId, targetSec ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (focusMode) startFocus(subjectId, categoryId, targetSec) else start(subjectId, categoryId, targetSec)
    }
    var confirmDiscard by remember { mutableStateOf(false) }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃本次计时？") },
            text = { Text("已累计的时间和尚未保存的结果都会丢弃。") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; discard() }) { Text("放弃") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("取消") } },
        )
    }
    // 结算页自己管滚动与底部操作条，其余两屏共用整页滚动。
    Box(Modifier.fillMaxSize()) {
        when {
            running != null -> Column(
                Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                RunningTimer(state, running, bootCount, end, { confirmDiscard = true }, pause, resume)
            }
            finished != null -> FinishedTimerForm(state, finished, busy, save, model, { confirmDiscard = true })
            else -> Column(
                Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                TimerSetup(
                    state, requestStart, addManual, createSubject, createCategory, rememberCustomMinutes,
                    focus = focusMode,
                    onFocusChange = { focusMode = it },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TimerSetup(
    state: AppState,
    start: (Long, Long, Int?) -> Unit,
    addManual: () -> Unit,
    createSubject: (String, (Long) -> Unit) -> Unit,
    createCategory: (Long, String, (Long) -> Unit) -> Unit,
    rememberCustomMinutes: (Int) -> Unit,
    focus: Boolean,
    onFocusChange: (Boolean) -> Unit,
) {
    // v5：主观题不再按名字隐藏；类别自带 defaultMode，结算页据此预填计分表单。
    val subjects = state.subjects.filter { !it.archived }
    val categories = state.categories.filter { !it.archived }
    var subjectId by rememberSaveable {
        mutableLongStateOf(
            state.sessions.firstOrNull()?.subjectId?.takeIf { id -> subjects.any { it.id == id } }
                ?: subjects.firstOrNull()?.id ?: 0
        )
    }
    val choices = categories.filter { it.subjectId == subjectId }
    var categoryId by rememberSaveable {
        mutableLongStateOf(
            state.sessions.firstOrNull { it.subjectId == subjectId }?.categoryId?.takeIf { id -> choices.any { it.id == id } }
                ?: choices.firstOrNull()?.id ?: 0
        )
    }
    var modeIndex by rememberSaveable { mutableIntStateOf(0) }
    val customIndex = TIMER_MODES.size
    var customText by rememberSaveable(state.customTimerMinutes) {
        mutableStateOf((state.customTimerMinutes ?: 30).toString())
    }
    val customMinutes = customText.toIntOrNull()
        ?.takeIf { it in SettingsRepository.MIN_CUSTOM_MINUTES..SettingsRepository.MAX_CUSTOM_MINUTES }
    val customSelected = modeIndex == customIndex
    Text(if (focus) "开始一段专注" else "准备好就开始", style = MaterialTheme.typography.headlineSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !focus,
            onClick = { onFocusChange(false) },
            label = { Text("练习计时", style = MaterialTheme.typography.bodySmall) },
            shape = RoundedCornerShape(2.dp),
            modifier = Modifier.heightIn(min = 44.dp),
        )
        FilterChip(
            selected = focus,
            onClick = { onFocusChange(true) },
            label = { Text("专注计时", style = MaterialTheme.typography.bodySmall) },
            shape = RoundedCornerShape(2.dp),
            modifier = Modifier.heightIn(min = 44.dp),
        )
    }
    Text(
        if (focus) "专注只记录时长：不填题量、不填得分，也不进入正确率。日期和起止时间会自动记下。"
        else "选好科目与类别，按下开始；日期和起止时间都会自动记下。中途离开此页不会打断计时。",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ChoiceField(
        "科目",
        state.subjects.find { it.id == subjectId }?.name.orEmpty(),
        subjects.map { it.id to it.name },
        createLabel = "新建科目",
        onCreate = { name -> createSubject(name) { id -> subjectId = id; categoryId = 0 } },
    ) {
        if (it != subjectId) {
            subjectId = it
            categoryId = categories.firstOrNull { category -> category.subjectId == it }?.id ?: 0
        }
    }
    ChoiceField(
        "类别",
        state.categories.find { it.id == categoryId }?.name.orEmpty(),
        choices.map { it.id to it.name },
        createLabel = "新建类别",
        onCreate = { name -> createCategory(subjectId, name) { id -> categoryId = id } },
    ) {
        categoryId = it
    }
    // 分类管理允许把科目/类别归档到只剩空壳；这里给出可执行的下一步，而不是让开始按钮无故变灰。
    if (subjects.isEmpty()) {
        Text(
            "还没有启用的科目。去「我的 › 分类管理」恢复或新建一个，才能开始计时。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    } else if (choices.isEmpty()) {
        Text(
            "这个科目还没有启用的类别。展开「类别」新建一个，或去「我的 › 分类管理」调整。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("练习日期", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("今天 · ${state.today.format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA))}", style = MaterialTheme.typography.bodyMedium)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("计时方式", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TIMER_MODES.forEachIndexed { index, mode ->
                FilterChip(
                    selected = modeIndex == index,
                    onClick = { modeIndex = index },
                    label = { Text(mode.label, style = MaterialTheme.typography.bodySmall) },
                    shape = RoundedCornerShape(2.dp),
                    colors = inkChipColors(),
                    modifier = Modifier.heightIn(min = 44.dp),
                )
            }
            FilterChip(
                selected = customSelected,
                onClick = { modeIndex = customIndex },
                label = { Text("自定义", style = MaterialTheme.typography.bodySmall) },
                shape = RoundedCornerShape(2.dp),
                colors = inkChipColors(),
                modifier = Modifier.heightIn(min = 44.dp),
            )
        }
        if (customSelected) {
            OutlinedTextField(
                value = customText,
                onValueChange = { text -> customText = text.filter { it.isDigit() }.take(3) },
                label = { Text("分钟数") },
                singleLine = true,
                isError = customText.isNotEmpty() && customMinutes == null,
                supportingText = {
                    Text(
                        if (customMinutes == null) {
                            "请输入 ${SettingsRepository.MIN_CUSTOM_MINUTES}–${SettingsRepository.MAX_CUSTOM_MINUTES} 之间的整数。"
                        } else {
                            "到点自动结算，中途可以暂停。"
                        }
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!customSelected) {
            Text(
                TIMER_MODES[modeIndex].hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val startTarget = if (customSelected) customMinutes?.times(60) else TIMER_MODES[modeIndex].targetSec
    Button(
        onClick = {
            if (customSelected) rememberCustomMinutes(customMinutes!!)
            start(subjectId, categoryId, startTarget)
        },
        enabled = subjectId > 0 && categoryId > 0 && (!customSelected || customMinutes != null),
        shape = RoundedCornerShape(2.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Text(
            when {
                customSelected && customMinutes != null -> "开始 $customMinutes 分钟${if (focus) "专注" else "倒计时"}"
                customSelected -> "开始倒计时"
                focus && TIMER_MODES[modeIndex].targetSec == null -> "开始专注"
                focus -> "开始 ${TIMER_MODES[modeIndex].label}专注"
                TIMER_MODES[modeIndex].targetSec == null -> "开始计时"
                else -> "开始 ${TIMER_MODES[modeIndex].label}倒计时"
            },
            style = MaterialTheme.typography.titleMedium,
        )
    }
    Text(
        if (focus) "结束后只确认专注时长，不用填题量或得分。" else "结束后回来填写题量与错题数。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = addManual, modifier = Modifier.fillMaxWidth()) { Text("改为补记一次", style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun RunningTimer(
    state: AppState,
    running: TimerStart,
    bootCount: Int,
    end: () -> Unit,
    requestDiscard: () -> Unit,
    pause: () -> Unit,
    resume: () -> Unit,
) {
    val elapsed by produceState(
        initialValue = SessionTimer.elapsedSeconds(running, System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootCount).toLong(),
        running,
        bootCount,
    ) {
        while (true) {
            value = SessionTimer.elapsedSeconds(running, System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootCount).toLong()
            delay(1_000)
        }
    }
    val remaining = running.targetSec?.let { (it - elapsed).coerceAtLeast(0L) }
    Text(
        when {
            running.isPaused -> "已暂停"
            running.focus -> "专注进行中"
            else -> "计时进行中"
        },
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        "${state.subjects.find { it.id == running.subjectId }?.name.orEmpty()} · ${state.categories.find { it.id == running.categoryId }?.name ?: "已选类别"}",
        style = MaterialTheme.typography.titleMedium,
    )
    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(2.dp)) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (running.isCountdown) "剩余" else "已计时", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f))
            Text(formatElapsed(remaining ?: elapsed), style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onPrimary)
            Text(
                if (running.isCountdown) {
                    "已用 ${formatElapsed(elapsed)} · 目标 ${running.targetSec!! / 60} 分钟"
                } else {
                    "开始于 ${dateLabel(running.startAt)} ${timeOfDay(running.startAt)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
            )
            if (running.pauseCount > 0) {
                Text(
                    "已暂停 ${running.pauseCount} 次，累计 ${running.pausedTotalSec / 60} 分 ${running.pausedTotalSec % 60} 秒不计入用时",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f),
                )
            }
        }
    }
    Text("离开此页不会中断计时，回到今日页可以再次进入。结束后请如实填写题量与错题数。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (running.isPaused) {
            Button(onClick = resume, shape = RoundedCornerShape(2.dp), modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("继续计时") }
        } else {
            OutlinedButton(onClick = pause, shape = RoundedCornerShape(2.dp), modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("暂停") }
        }
        Button(onClick = end, shape = RoundedCornerShape(2.dp), modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
            Text(if (running.isCountdown) "提前结束" else "结束计时")
        }
    }
    TextButton(onClick = requestDiscard, modifier = Modifier.fillMaxWidth()) { Text("放弃本次计时") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FinishedTimerForm(
    state: AppState,
    finished: FinishedTimer,
    busy: Boolean,
    save: (Session) -> Unit,
    model: MainViewModel,
    requestDiscard: () -> Unit,
) {
    if (finished.focus) {
        FocusResultForm(state, finished, busy, save, requestDiscard)
        return
    }
    val category = state.categories.find { it.id == finished.categoryId }
    val isRedo = state.redoBatchId != null
    var modeKey by rememberSaveable(finished.startAt) {
        mutableStateOf(if (isRedo) RecordMode.COUNT.name else (category?.defaultMode ?: RecordMode.COUNT).name)
    }
    val mode = if (isRedo) RecordMode.COUNT else RecordMode.fromKey(modeKey)
    var total by rememberSaveable(finished.startAt) {
        mutableStateOf(if (!isRedo && category?.defaultMode == RecordMode.SCORE) "1" else defaultCount(state.sessions, finished.categoryId))
    }
    var wrong by rememberSaveable(finished.startAt) { mutableStateOf("") }
    var wrongTags by rememberSaveable(finished.startAt) { mutableStateOf<String?>(null) }
    var difficulty by rememberSaveable(finished.startAt) { mutableIntStateOf(0) }
    var energy by rememberSaveable(finished.startAt) { mutableIntStateOf(0) }
    var error by rememberSaveable(finished.startAt) { mutableStateOf<String?>(null) }
    var correctedMinutes by rememberSaveable(finished.startAt) { mutableStateOf("") }
    var scoreLabel by rememberSaveable(finished.startAt) { mutableStateOf("") }
    var score by rememberSaveable(finished.startAt) { mutableStateOf("") }
    var maxScore by rememberSaveable(finished.startAt) { mutableStateOf(category?.defaultMaxScoreTenths?.let(ScoreRules::format) ?: "") }
    var createBag by rememberSaveable(finished.startAt) { mutableStateOf(false) }
    var bagCount by rememberSaveable(finished.startAt) { mutableStateOf("") }
    val redoRemaining = state.redoBatchId?.let { id ->
        state.wrongBatches.find { it.id == id }?.let { batch ->
            RedoLedger.ledger(batch, state.reviewAttempts.filter { attempt -> attempt.batchId == id }, state.today).remaining
        }
    }
    val totalValue = total.toIntOrNull()
    val wrongValue = wrong.toIntOrNull()
    val correctedMinutesValue = correctedMinutes.toIntOrNull()
    val correctedSeconds = correctedMinutesValue
        ?.takeIf { it in 1 until FinishedTimer.LONG_RUNNING_SECONDS / 60 }
        ?.times(60)
    val endText = if (dateLabel(finished.endAt) == dateLabel(finished.startAt)) timeOfDay(finished.endAt)
    else "${dateLabel(finished.endAt)} ${timeOfDay(finished.endAt)}"
    val scroll = rememberScrollState()
    val reveal = rememberImeReveal(scroll)
    Box(
        Modifier.fillMaxSize().imePadding()
            .onGloballyPositioned { reveal.viewportBottomPx = it.boundsInWindow().bottom.roundToInt() },
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).verticalScroll(scroll)
                    .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(if (finished.isLongRunning) "本次计时待确认" else "本次计时已完成", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${state.subjects.find { it.id == finished.subjectId }?.name.orEmpty()} · ${state.categories.find { it.id == finished.categoryId }?.name ?: "已选类别"}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(2.dp)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("本次用时", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f))
                        Text(formatElapsed(finished.durationSec.toLong()), style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onPrimary)
                        Text(
                            "${dateLabel(finished.startAt)} ${timeOfDay(finished.startAt)} – $endText",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                        )
                        Text(
                            "练习日期 ${dateLabel(finished.startAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f),
                        )
                    }
                }
                if (finished.isLongRunning) {
                    Text(
                        "这次计时超过 4 小时，可能与实际不符。请填写实际用时（分钟）后再保存；如果不记得，直接作废本次计时。",
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedTextField(
                        correctedMinutes,
                        { correctedMinutes = CountInput.sanitize(it, 3) },
                        label = { Text("实际用时（分钟）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().revealWhenFocused(reveal),
                    )
                    if (correctedSeconds != null) {
                        val corrected = SessionTimer.correctDuration(finished, correctedSeconds)
                        Text(
                            "将保存为 ${formatElapsed(correctedSeconds.toLong())} · ${timeOfDay(corrected.endAt)} 结束",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (isRedo) {
                    Text(
                        "错题复做${redoRemaining?.let { " · 本袋还剩 $it 题" }.orEmpty()}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "填写本轮重做题数与通过数；复做计入题量与时长，但不计入主正确率。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { model.cancelRedo(); requestDiscard() }) { Text("退出这次复做，作废本次计时") }
                } else {
                    RecordModeSelector(
                        selected = mode,
                        onSelect = { next ->
                            modeKey = next.name
                            total = if (next == RecordMode.SCORE) "1" else defaultCount(state.sessions, finished.categoryId)
                            wrong = ""; score = ""; scoreLabel = ""; createBag = false; bagCount = ""
                            error = null
                        },
                        hasIncompatibleInput = wrong.isNotBlank() || score.isNotBlank() || scoreLabel.isNotBlank(),
                        hasPracticeInput = total.isNotBlank(),
                        enabled = !busy,
                    )
                    TextButton(
                        onClick = { model.setCategoryDefaultMode(finished.categoryId, mode) },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "把「${if (mode == RecordMode.SCORE) "计分" else "客观题"}」设为这个类别的默认",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Text("填写本次结果", style = MaterialTheme.typography.titleMedium)
                if (mode == RecordMode.SCORE) {
                    OutlinedTextField(
                        total,
                        { total = CountInput.sanitize(it) },
                        label = { Text("题量（整篇作文写 1）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().revealWhenFocused(reveal),
                    )
                    Text(
                        "计分记录仍要填题量，用于题量、时长与热力等统一投入面；答对数记为「不适用」，不显示为 0%。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ScoreFields(
                        label = scoreLabel, onLabel = { scoreLabel = it; error = null },
                        score = score, onScore = { score = it; error = null },
                        maxScore = maxScore, onMax = { maxScore = it; error = null },
                        rememberedMaxTenths = category?.defaultMaxScoreTenths,
                        enabled = !busy,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = createBag, onCheckedChange = { createBag = it }, enabled = !busy)
                        Text("把这次要重看的点单独建一个错题袋（选填）", style = MaterialTheme.typography.bodySmall)
                    }
                    if (createBag) {
                        OutlinedTextField(
                            bagCount,
                            { bagCount = CountInput.sanitize(it); error = null },
                            label = { Text("要重看的题数") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().revealWhenFocused(reveal),
                        )
                        Text(
                            "计分题没有可定义的「错题数」，所以只有你主动填写才会建袋。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            total,
                            { total = CountInput.sanitize(it) },
                            label = { Text("题量") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f).revealWhenFocused(reveal),
                        )
                        OutlinedTextField(
                            wrong,
                            { wrong = CountInput.sanitize(it) },
                            label = { Text(if (isRedo) "未通过数" else "错题数") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f).revealWhenFocused(reveal),
                        )
                    }
                    if (totalValue != null && totalValue > 0 && wrongValue != null && wrongValue in 0..totalValue) {
                        Text(
                            if (isRedo) "本轮通过 ${totalValue - wrongValue} / $totalValue 题（只进复做轨）"
                            else "答对 ${totalValue - wrongValue} / $totalValue 题（单次结果）",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (mode == RecordMode.SCORE || isRedo || (wrongValue ?: 0) > 0 || TagRules.parse(wrongTags).isNotEmpty()) {
                    WrongTagField(
                        value = wrongTags,
                        onChange = { wrongTags = it; error = null },
                        recent = state.recentTags,
                        modifier = Modifier.revealWhenFocused(reveal),
                    )
                }
                Text("保存后可在记录中编辑，并补填逐题对错。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.settings.selfAssessment) {
                    RatingRow("难度", listOf("未填", "易", "中", "难"), difficulty) { difficulty = it }
                    RatingRow("精力", listOf("未填", "低", "中", "高"), energy) { energy = it }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    enabled = !busy,
                    shape = RoundedCornerShape(2.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                    onClick = {
                        val tagError = TagRules.error(wrongTags)
                        val scoreProblem = if (mode == RecordMode.SCORE) scoreInputError(scoreLabel, score, maxScore) else null
                        val bagCountValue = bagCount.toIntOrNull()
                        error = when {
                            totalValue == null || totalValue <= 0 -> "题量必须是大于 0 的整数。"
                            mode == RecordMode.COUNT && (wrongValue == null || wrongValue !in 0..totalValue) ->
                                if (isRedo) "未通过数需要在 0 与题量之间。" else "错题数需要在 0 与题量之间。"
                            isRedo && redoRemaining != null && totalValue > redoRemaining ->
                                "本轮最多只能重做袋里剩余的 $redoRemaining 题。"
                            scoreProblem != null -> scoreProblem
                            mode == RecordMode.SCORE && createBag && (bagCountValue == null || bagCountValue <= 0) ->
                                "请填写要重看的题数。"
                            finished.isLongRunning && correctedMinutes.isBlank() -> "请填写实际用时（分钟）。"
                            finished.isLongRunning && correctedSeconds == null -> "实际用时需要小于 4 小时；更长的练习请分次记录。"
                            tagError != null -> tagError
                            else -> null
                        }
                        if (error == null) {
                            val recorded = if (finished.isLongRunning && correctedSeconds != null) {
                                SessionTimer.correctDuration(finished, correctedSeconds)
                            } else {
                                finished
                            }
                            val scoreTenths = if (mode == RecordMode.SCORE) ScoreRules.parseTenths(score) else null
                            val maxTenths = if (mode == RecordMode.SCORE) ScoreRules.parseTenths(maxScore) else null
                            val session = Session(
                                categoryId = recorded.categoryId,
                                subjectId = recorded.subjectId,
                                startAt = recorded.startAt,
                                endAt = recorded.endAt,
                                durationSec = recorded.durationSec,
                                pausedSec = recorded.pausedSec,
                                pauseCount = recorded.pauseCount,
                                totalCount = totalValue!!,
                                correctCount = if (mode == RecordMode.SCORE) 0 else totalValue - wrongValue!!,
                                mode = mode,
                                kind = if (isRedo) SessionKind.REDO else SessionKind.NORMAL,
                                scoreTenths = scoreTenths,
                                maxScoreTenths = maxTenths,
                                scoreLabel = scoreLabel.takeIf { mode == RecordMode.SCORE && it.isNotBlank() },
                                batchId = if (isRedo) state.redoBatchId else null,
                                wrongTags = TagRules.normalize(wrongTags),
                                difficulty = difficulty,
                                energy = energy,
                                source = SessionSource.TIMER,
                                createdAt = System.currentTimeMillis(),
                            )
                            if (mode == RecordMode.SCORE) {
                                model.rememberMaxScore(recorded.categoryId, maxTenths)
                                if (createBag && bagCountValue != null) {
                                    model.saveScoreSessionWithBatch(session, bagCountValue)
                                } else {
                                    save(session)
                                }
                            } else {
                                save(session)
                            }
                        }
                    },
                ) { Text(if (busy) "正在保存…" else if (finished.isLongRunning) "确认用时并保存" else "保存练习") }
                TextButton(onClick = requestDiscard, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (finished.isLongRunning) "作废本次计时" else "重新计时")
                }
            }
        }
    }
}

/**
 * v0.11 专注结算页：只确认时长。
 * 这里刻意不出现题量、答对数、得分、错题关键词、错题袋与难度/精力自评，
 * 避免用户在没有做题的时段里被迫编数字。
 */
@Composable
private fun FocusResultForm(
    state: AppState,
    finished: FinishedTimer,
    busy: Boolean,
    save: (Session) -> Unit,
    requestDiscard: () -> Unit,
) {
    var error by rememberSaveable(finished.startAt) { mutableStateOf<String?>(null) }
    var correctedMinutes by rememberSaveable(finished.startAt) { mutableStateOf("") }
    val correctedMinutesValue = correctedMinutes.toIntOrNull()
    val correctedSeconds = correctedMinutesValue
        ?.takeIf { it in 1 until FinishedTimer.LONG_RUNNING_SECONDS / 60 }
        ?.times(60)
    val shownDuration = if (finished.isLongRunning && correctedSeconds != null) correctedSeconds else finished.durationSec
    val endText = if (dateLabel(finished.endAt) == dateLabel(finished.startAt)) timeOfDay(finished.endAt)
    else "${dateLabel(finished.endAt)} ${timeOfDay(finished.endAt)}"
    val scroll = rememberScrollState()
    val reveal = rememberImeReveal(scroll)
    Box(
        Modifier.fillMaxSize().imePadding()
            .onGloballyPositioned { reveal.viewportBottomPx = it.boundsInWindow().bottom.roundToInt() },
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).verticalScroll(scroll)
                    .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(if (finished.isLongRunning) "本次专注待确认" else "本次专注已结束", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${state.subjects.find { it.id == finished.subjectId }?.name.orEmpty()} · ${state.categories.find { it.id == finished.categoryId }?.name ?: "已选类别"}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(2.dp)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("本次专注", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f))
                        Text(formatElapsed(shownDuration.toLong()), style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onPrimary)
                        Text(
                            "${dateLabel(finished.startAt)} ${timeOfDay(finished.startAt)} – $endText",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                        )
                        Text(
                            "专注日期 ${dateLabel(finished.startAt)}" +
                                if (finished.pauseCount > 0) " · 暂停 ${finished.pauseCount} 次共 ${formatElapsed(finished.pausedSec.toLong())}" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f),
                        )
                    }
                }
                if (finished.isLongRunning) {
                    Text(
                        "这次计时超过 4 小时，可能与实际不符。请填写实际专注时长（分钟）后再保存；如果不记得，直接作废本次计时。",
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedTextField(
                        correctedMinutes,
                        { correctedMinutes = CountInput.sanitize(it, 3); error = null },
                        label = { Text("实际专注时长（分钟）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().revealWhenFocused(reveal),
                    )
                    if (correctedSeconds != null) {
                        val corrected = SessionTimer.correctDuration(finished, correctedSeconds)
                        Text(
                            "将保存为 ${formatElapsed(correctedSeconds.toLong())} · ${timeOfDay(corrected.endAt)} 结束",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text("专注只记时长", style = MaterialTheme.typography.titleMedium)
                Text(
                    "这条记录会计入净时长、热力图、连续天数与时段分布，并计入每日时长目标；不填题量、不产生正确率，也不进入错题复做。" +
                        "专注也不做难度/精力自评——这两项只在有题可评判时才成立。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    enabled = !busy,
                    shape = RoundedCornerShape(2.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                    onClick = {
                        error = when {
                            finished.isLongRunning && correctedMinutes.isBlank() -> "请填写实际专注时长（分钟）。"
                            finished.isLongRunning && correctedSeconds == null ->
                                "实际时长需要小于 4 小时；更长的专注请分次记录。"
                            else -> null
                        }
                        if (error == null) {
                            val recorded = if (finished.isLongRunning && correctedSeconds != null) {
                                SessionTimer.correctDuration(finished, correctedSeconds)
                            } else {
                                finished
                            }
                            save(
                                Session(
                                    categoryId = recorded.categoryId,
                                    subjectId = recorded.subjectId,
                                    startAt = recorded.startAt,
                                    endAt = recorded.endAt,
                                    durationSec = recorded.durationSec,
                                    pausedSec = recorded.pausedSec,
                                    pauseCount = recorded.pauseCount,
                                    totalCount = 0,
                                    correctCount = 0,
                                    mode = RecordMode.FOCUS,
                                    kind = SessionKind.NORMAL,
                                    source = SessionSource.TIMER,
                                    createdAt = System.currentTimeMillis(),
                                ),
                            )
                        }
                    },
                ) { Text(if (busy) "正在保存…" else "保存专注") }
                TextButton(onClick = requestDiscard, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("作废本次计时")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceField(
    label: String,
    selected: String,
    options: List<Pair<Long, String>>,
    createLabel: String? = null,
    onCreate: ((String) -> Unit)? = null,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    if (creating && createLabel != null && onCreate != null) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(createLabel) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        draft,
                        { draft = it; hint = null },
                        label = { Text("名称") },
                        singleLine = true,
                        isError = hint != null,
                        supportingText = { Text(hint ?: "最多 ${NameRules.MAX_LENGTH} 个字，留空会被拒绝。") },
                    )
                    Text("新建后立刻出现在列表里，之后也能继续复用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val problem = NameRules.error(draft)
                    if (problem != null) {
                        hint = problem
                    } else {
                        onCreate(draft)
                        creating = false
                        draft = ""
                    }
                }) { Text("新建") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("取消") } },
        )
    }
    ExposedDropdownMenuBox(expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected, onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(id); expanded = false }) }
            if (createLabel != null && onCreate != null) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("＋ $createLabel", color = MaterialTheme.colorScheme.primary) },
                    onClick = { expanded = false; draft = ""; hint = null; creating = true },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordEditor(
    state: AppState,
    existing: Session?,
    busy: Boolean,
    save: (Session) -> Unit,
    createSubject: (String, (Long) -> Unit) -> Unit,
    createCategory: (Long, String, (Long) -> Unit) -> Unit,
    model: MainViewModel,
) {
    // v5：主观题科目重新可见，改用「客观题 / 计分」两种记录方式区分。
    // 已归档的科目/类别不能再选，但历史记录仍要能编辑：把这条记录自己的归属补回列表（D35）。
    val ownSubject = existing?.let { row -> state.subjects.find { it.id == row.subjectId } }
    val ownCategory = existing?.let { row -> state.categories.find { it.id == row.categoryId } }
    val availableSubjects = (
        state.subjects.filter { !it.archived } +
            listOfNotNull(ownSubject?.takeIf { it.archived })
        ).distinctBy { it.id }.sortedBy { it.sortOrder }
    val availableCategories = (
        state.categories.filter { !it.archived } +
            listOfNotNull(ownCategory?.takeIf { it.archived })
        ).distinctBy { it.id }
    var subjectId by rememberSaveable(existing?.id) { mutableLongStateOf(existing?.subjectId ?: state.sessions.firstOrNull()?.subjectId?.takeIf { id -> availableSubjects.any { it.id == id } } ?: availableSubjects.firstOrNull()?.id ?: 0) }
    val choices = availableCategories.filter { it.subjectId == subjectId }
    var categoryId by rememberSaveable(existing?.id) { mutableLongStateOf(existing?.categoryId ?: state.sessions.firstOrNull { it.subjectId == subjectId }?.categoryId?.takeIf { id -> choices.any { it.id == id } } ?: choices.firstOrNull()?.id ?: 0) }
    val selectedCategory = state.categories.find { it.id == categoryId }
    val isExistingRedo = existing?.kind == SessionKind.REDO
    var modeKey by rememberSaveable(existing?.id) {
        mutableStateOf((existing?.mode ?: selectedCategory?.defaultMode ?: RecordMode.COUNT).name)
    }
    val mode = if (isExistingRedo) RecordMode.COUNT else RecordMode.fromKey(modeKey)
    // v0.11：专注记录只改日期、时长、科目类别；题量/得分/关键词一律不出现（D27）。
    val isFocusRecord = mode == RecordMode.FOCUS
    var scoreLabel by rememberSaveable(existing?.id) { mutableStateOf(existing?.scoreLabel ?: "") }
    var score by rememberSaveable(existing?.id) { mutableStateOf(existing?.scoreTenths?.let(ScoreRules::format) ?: "") }
    var maxScore by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.maxScoreTenths?.let(ScoreRules::format) ?: selectedCategory?.defaultMaxScoreTenths?.let(ScoreRules::format) ?: "")
    }
    var createBag by rememberSaveable(existing?.id) { mutableStateOf(false) }
    var bagCount by rememberSaveable(existing?.id) { mutableStateOf("") }
    var total by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.totalCount?.toString() ?: if (mode == RecordMode.SCORE) "1" else defaultCount(state.sessions, categoryId))
    }
    var wrong by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.takeIf { it.mode == RecordMode.COUNT }?.let { (it.totalCount - it.correctCount).toString() } ?: "")
    }
    var date by rememberSaveable(existing?.id) { mutableStateOf(existing?.let { sessionDate(it, state.settings.dateAttribution).toString() } ?: LocalDate.now().toString()) }
    var startText by rememberSaveable(existing?.id) {
        mutableStateOf(
            existing?.takeIf { it.source != SessionSource.TIMER && it.endAt != null }?.let { timeOfDay(it.startAt) } ?: "",
        )
    }
    var endText by rememberSaveable(existing?.id) {
        mutableStateOf(
            existing?.takeIf { it.source != SessionSource.TIMER }?.endAt?.let { timeOfDay(it) } ?: "",
        )
    }
    var minutes by rememberSaveable(existing?.id) { mutableStateOf(existing?.durationSec?.let { (it / 60).toString() } ?: "") }
    var seconds by rememberSaveable(existing?.id) { mutableStateOf(existing?.durationSec?.let { (it % 60).toString() } ?: "") }
    var sequence by rememberSaveable(existing?.id) { mutableStateOf(existing?.answerSequence) }
    var wrongTags by rememberSaveable(existing?.id) { mutableStateOf(existing?.wrongTags) }
    var difficulty by rememberSaveable(existing?.id) { mutableIntStateOf(existing?.difficulty ?: 0) }
    var energy by rememberSaveable(existing?.id) { mutableIntStateOf(existing?.energy ?: 0) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val totalValue = total.toIntOrNull()
    val wrongValue = wrong.toIntOrNull()
    val clockStart = ClockInput.parse(startText)
    val clockEnd = ClockInput.parse(endText)
    val clockFilled = startText.isNotBlank() || endText.isNotBlank()
    val clockSeconds = if (clockStart != null && clockEnd != null) ClockInput.durationSeconds(clockStart, clockEnd) else null
    val scroll = rememberScrollState()
    val reveal = rememberImeReveal(scroll)
    Box(
        Modifier.fillMaxSize().imePadding()
            .onGloballyPositioned { reveal.viewportBottomPx = it.boundsInWindow().bottom.roundToInt() },
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).verticalScroll(scroll)
                    .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    when {
                        isFocusRecord -> if (existing == null) "补记一段专注" else "修正这段专注的时长"
                        existing == null -> "留下刚刚完成的一组"
                        else -> "修正记录，保留真实进步"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    if (isFocusRecord) "只填科目、类别与时长；题量、得分、错题都不需要。" else "先记录结果，时间和逐题回填都可以留空。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChoiceField(
                    "科目",
                    state.subjects.find { it.id == subjectId }?.name.orEmpty(),
                    availableSubjects.map { it.id to it.name },
                    createLabel = "新建科目",
                    onCreate = { name -> createSubject(name) { id -> subjectId = id; categoryId = 0 } },
                ) {
                    if (it != subjectId) {
                        subjectId = it
                        categoryId = availableCategories.firstOrNull { category -> category.subjectId == it }?.id ?: 0
                        if (existing == null) {
                            total = defaultCount(state.sessions, categoryId)
                            sequence = null
                        }
                    }
                }
                ChoiceField(
                    "类别",
                    state.categories.find { it.id == categoryId }?.name.orEmpty(),
                    choices.map { it.id to it.name },
                    createLabel = "新建类别",
                    onCreate = { name -> createCategory(subjectId, name) { id -> categoryId = id } },
                ) {
                    if (it != categoryId) {
                        categoryId = it
                        if (existing == null) {
                            total = defaultCount(state.sessions, it)
                            sequence = null
                        }
                    }
                }
                if (isExistingRedo) {
                    Text(
                        "这是一条错题复做记录；编辑时保留复做性质与错题袋关联，不计入主正确率。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    RecordModeSelector(
                        selected = mode,
                        onSelect = { next ->
                            modeKey = next.name
                            total = when (next) {
                                RecordMode.SCORE -> "1"
                                RecordMode.FOCUS -> ""
                                RecordMode.COUNT -> defaultCount(state.sessions, categoryId)
                            }
                            wrong = ""; score = ""; scoreLabel = ""; sequence = null
                            createBag = false; bagCount = ""; error = null
                        },
                        hasIncompatibleInput = wrong.isNotBlank() || score.isNotBlank() || scoreLabel.isNotBlank(),
                        hasPracticeInput = total.isNotBlank(),
                        enabled = !busy,
                    )
                    if (!isFocusRecord) {
                        TextButton(
                            onClick = { model.setCategoryDefaultMode(categoryId, mode) },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                        ) {
                            Text(
                                "把「${if (mode == RecordMode.SCORE) "计分" else "客观题"}」设为这个类别的默认",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                OutlinedTextField(date, { date = it }, label = { Text(if (isFocusRecord) "专注日期" else "练习日期") }, supportingText = { Text("例如 2026-10-28") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (existing == null || existing.source != SessionSource.TIMER) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            startText, { startText = it; error = null }, label = { Text("开始时间（选填）") },
                            placeholder = { Text("21:16") }, singleLine = true, modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            endText, { endText = it; error = null }, label = { Text("结束时间（选填）") },
                            placeholder = { Text("21:26") }, singleLine = true, modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        "填了起止时刻就按真实时段记账，并计入常练时段；跨零点写 24:03。只填一个不算数。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (mode == RecordMode.SCORE) {
                    OutlinedTextField(
                        total,
                        { total = CountInput.sanitize(it); sequence = null },
                        label = { Text("题量（整篇作文写 1）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().revealWhenFocused(reveal),
                    )
                    ScoreFields(
                        label = scoreLabel, onLabel = { scoreLabel = it; error = null },
                        score = score, onScore = { score = it; error = null },
                        maxScore = maxScore, onMax = { maxScore = it; error = null },
                        rememberedMaxTenths = selectedCategory?.defaultMaxScoreTenths,
                        enabled = !busy,
                    )
                    if (existing == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = createBag, onCheckedChange = { createBag = it }, enabled = !busy)
                            Text("把这次要重看的点单独建一个错题袋（选填）", style = MaterialTheme.typography.bodySmall)
                        }
                        if (createBag) {
                            OutlinedTextField(
                                bagCount,
                                { bagCount = CountInput.sanitize(it); error = null },
                                label = { Text("要重看的题数") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().revealWhenFocused(reveal),
                            )
                        }
                    }
                } else if (isFocusRecord) {
                    Text(
                        "专注记录只填时长，没有题量与对错。要记做题结果，请切到「正确率」或「得分」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            total,
                            { total = CountInput.sanitize(it); sequence = null },
                            label = { Text("题量") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f).revealWhenFocused(reveal),
                        )
                        OutlinedTextField(
                            wrong,
                            { wrong = CountInput.sanitize(it); sequence = null },
                            label = { Text(if (isExistingRedo) "未通过数" else "错题数") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f).revealWhenFocused(reveal),
                        )
                    }
                    if (totalValue != null && totalValue > 0 && wrongValue != null && wrongValue in 0..totalValue) {
                        Text(
                            if (isExistingRedo) "本轮通过 ${totalValue - wrongValue} / $totalValue 题（只进复做轨）"
                            else "答对 ${totalValue - wrongValue} / $totalValue 题（单次结果）",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (!isFocusRecord && (mode == RecordMode.SCORE || (wrongValue ?: 0) > 0 || TagRules.parse(wrongTags).isNotEmpty())) {
                    WrongTagField(
                        value = wrongTags,
                        onChange = { wrongTags = it; error = null },
                        recent = state.recentTags,
                        modifier = Modifier.revealWhenFocused(reveal),
                    )
                }
                Text(if (isFocusRecord) "专注时长（必填）" else "用时（可选）", style = MaterialTheme.typography.titleMedium)
                if (clockSeconds != null) {
                    Text(
                        "按起止时刻自动计算：${clockSeconds / 60} 分 ${clockSeconds % 60} 秒",
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            minutes,
                            { minutes = CountInput.sanitize(it) },
                            label = { Text("分钟") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f).revealWhenFocused(reveal),
                        )
                        OutlinedTextField(
                            seconds,
                            { seconds = CountInput.sanitize(it, 2) },
                            label = { Text("秒") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f).revealWhenFocused(reveal),
                        )
                    }
                }
                Text(
                    if (isFocusRecord) "专注必须有一段真实时长；起止时刻可留空，留空时只按分钟记账。"
                    else "留空表示未计时，不会影响正确率统计。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (mode == RecordMode.COUNT && totalValue != null && totalValue in 1..200) {
                    TextButton(onClick = {
                        sequence = if (sequence == null) "1".repeat(totalValue) else null
                        if (sequence != null) wrong = "0"
                    }) { Text(if (sequence == null) "逐题回填（可选）" else "关闭回填，保留错题数") }
                    sequence?.let { answers ->
                        Text("点击做错的题，再点一次改回应答正确；初始全部为对。", style = MaterialTheme.typography.bodyMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            answers.forEachIndexed { index, answer ->
                                FilterChip(
                                    selected = answer == '0',
                                    onClick = {
                                        sequence = answers.toCharArray().also { it[index] = if (answer == '0') '1' else '0' }.concatToString()
                                        wrong = sequence!!.count { it == '0' }.toString()
                                    },
                                    label = { Text("${index + 1} ${if (answer == '0') "错" else "对"}") },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                )
                            }
                        }
                    }
                } else if (mode == RecordMode.COUNT && totalValue != null && totalValue > 200) {
                    Text("本次超过 200 题，请直接填写错题总数。", style = MaterialTheme.typography.bodySmall)
                }
                if (state.settings.selfAssessment && !isFocusRecord) {
                    RatingRow("难度", listOf("未填", "易", "中", "难"), difficulty) { difficulty = it }
                    RatingRow("精力", listOf("未填", "低", "中", "高"), energy) { energy = it }
                }
                Text(
                    when {
                        isFocusRecord -> "专注只进投入面：算时长、热力与连续天数，不算正确率，也不进错题复做。"
                        mode == RecordMode.SCORE -> "计分记录只进得分轨，不进主正确率；题量仍用于时长与热力。"
                        else -> "英语作文、申论等主观题请切到「计分」；只记时长不动笔的时段请切到「专注」。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    enabled = !busy,
                    shape = RoundedCornerShape(2.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                    onClick = {
                        val parsedDate = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
                        val minuteValue = if (minutes.isBlank()) 0 else minutes.toIntOrNull()
                        val secondValue = if (seconds.isBlank()) 0 else seconds.toIntOrNull()
                        val duration = if (minutes.isBlank() && seconds.isBlank()) null else minuteValue?.toLong()?.times(60)?.plus(secondValue ?: 0)
                        val tagError = TagRules.error(wrongTags)
                        val scoreProblem = if (mode == RecordMode.SCORE) scoreInputError(scoreLabel, score, maxScore) else null
                        val bagCountValue = bagCount.toIntOrNull()
                        // 专注的净时长优先取起止时刻算出的值，其次取分钟/秒输入。
                        val effectiveSeconds = clockSeconds?.toLong() ?: duration
                        error = when {
                            parsedDate == null || parsedDate.isAfter(LocalDate.now()) -> "请输入有效的练习日期，不能晚于今天。"
                            !isFocusRecord && (totalValue == null || totalValue <= 0) -> "题量必须是大于 0 的整数。"
                            mode == RecordMode.COUNT && (wrongValue == null || totalValue == null || wrongValue !in 0..totalValue) ->
                                if (isExistingRedo) "未通过数需要在 0 与题量之间。" else "错题数需要在 0 与题量之间。"
                            scoreProblem != null -> scoreProblem
                            mode == RecordMode.SCORE && existing == null && createBag && (bagCountValue == null || bagCountValue <= 0) ->
                                "请填写要重看的题数。"
                            categoryId == 0L || choices.none { it.id == categoryId } -> "请选择有效的类别。"
                            clockFilled && (startText.isBlank() || endText.isBlank()) -> "起止时间要一起填，或一起留空。"
                            clockFilled && clockStart == null -> "开始时间格式应为 21:16。"
                            clockFilled && clockEnd == null -> "结束时间格式应为 21:26，跨零点可以写 24:03。"
                            clockFilled && clockSeconds == null -> "结束时间要晚于开始时间。"
                            !clockFilled && (minuteValue == null || minuteValue < 0 || secondValue == null || secondValue !in 0..59) -> "请检查用时：分钟不能为负数，秒数应为 0–59。"
                            !clockFilled && duration != null && (duration <= 0 || duration > Int.MAX_VALUE) -> "用时必须大于 0，未计时请留空。"
                            isFocusRecord && effectiveSeconds == null -> "专注记录必须填写时长。"
                            isFocusRecord && effectiveSeconds != null && effectiveSeconds < 60 ->
                                "专注时长至少 1 分钟；更短的片段请直接用「专注计时」记录。"
                            tagError != null -> tagError
                            else -> null
                        }
                        if (error == null) {
                            val zone = ZoneId.systemDefault()
                            val source = existing?.source ?: SessionSource.MANUAL
                            val existingDate = existing?.let { sessionDate(it, state.settings.dateAttribution) }
                            val keepMoment = existing != null && parsedDate == existingDate
                            val start = when {
                                clockStart != null -> parsedDate!!.atTime(clockStart.time).atZone(zone).toInstant().toEpochMilli()
                                keepMoment -> existing!!.startAt
                                source == SessionSource.TIMER -> {
                                    val existingTime = Instant.ofEpochMilli(existing!!.startAt).atZone(zone).toLocalTime()
                                    parsedDate!!.atTime(existingTime).atZone(zone).toInstant().toEpochMilli()
                                }
                                else -> parsedDate!!.atStartOfDay(zone).toInstant().toEpochMilli()
                            }
                            val endAt = when {
                                clockEnd != null -> parsedDate!!.plusDays(clockEnd.dayOffset.toLong()).atTime(clockEnd.time)
                                    .atZone(zone).toInstant().toEpochMilli()
                                source == SessionSource.MANUAL -> null
                                keepMoment -> existing!!.endAt
                                else -> existing!!.endAt?.let { start + (it - existing.startAt) }
                            }
                            val scoreTenths = if (mode == RecordMode.SCORE) ScoreRules.parseTenths(score) else null
                            val maxTenths = if (mode == RecordMode.SCORE) ScoreRules.parseTenths(maxScore) else null
                            val session = Session(
                                id = existing?.id ?: 0,
                                categoryId = categoryId, subjectId = subjectId, startAt = start, endAt = endAt,
                                durationSec = clockSeconds ?: duration?.toInt(),
                                totalCount = if (isFocusRecord) 0 else totalValue!!,
                                correctCount = if (isFocusRecord || mode == RecordMode.SCORE) 0 else totalValue!! - wrongValue!!,
                                mode = mode,
                                kind = if (isFocusRecord) SessionKind.NORMAL else existing?.kind ?: SessionKind.NORMAL,
                                scoreTenths = scoreTenths,
                                maxScoreTenths = maxTenths,
                                scoreLabel = scoreLabel.takeIf { mode == RecordMode.SCORE && it.isNotBlank() },
                                batchId = if (isFocusRecord) null else existing?.batchId,
                                answerSequence = if (isFocusRecord || mode == RecordMode.SCORE) null else sequence,
                                wrongTags = if (isFocusRecord) null else TagRules.normalize(wrongTags),
                                difficulty = if (isFocusRecord) 0 else difficulty,
                                energy = if (isFocusRecord) 0 else energy,
                                source = source,
                                prescriptionId = existing?.prescriptionId,
                                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                            )
                            if (mode == RecordMode.SCORE) {
                                model.rememberMaxScore(categoryId, maxTenths)
                                if (existing == null && createBag && bagCountValue != null) {
                                    model.saveScoreSessionWithBatch(session, bagCountValue)
                                } else {
                                    save(session)
                                }
                            } else {
                                save(session)
                            }
                        }
                    },
                ) { Text(if (busy) "正在保存…" else "保存练习") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RatingRow(label: String, choices: List<String>, selected: Int, choose: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEachIndexed { index, name ->
                FilterChip(
                    selected == index, { choose(index) },
                    label = { Text(name, style = MaterialTheme.typography.bodySmall) },
                    shape = RoundedCornerShape(2.dp),
                    colors = inkChipColors(),
                    modifier = Modifier.heightIn(min = 44.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(
    state: AppState,
    busy: Boolean,
    save: (UserSettings, ExamTarget) -> Unit,
    onExport: (android.net.Uri) -> Unit,
    switchExam: (ExamType) -> Unit,
    restorePreview: RestorePreview?,
    onExportBackup: (android.net.Uri) -> Unit,
    onPickBackupFolder: (android.net.Uri) -> Unit,
    onBackupToFolder: () -> Unit,
    onPreviewRestore: (android.net.Uri) -> Unit,
    onCancelRestore: () -> Unit,
    onConfirmRestore: (Boolean) -> Unit,
    onOpenImport: () -> Unit,
    onPreviewTheme: (ThemeMode?) -> Unit,
    onOpenCatalog: () -> Unit,
) {
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let(onExport)
    }
    val backupExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(onExportBackup)
    }
    val backupImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onPreviewRestore)
    }
    val backupFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(onPickBackupFolder)
    }
    var restorePreferences by rememberSaveable(restorePreview) { mutableStateOf(false) }
    restorePreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { if (!busy) onCancelRestore() },
            title = { Text("从备份恢复") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("备份时间：${formatBackupTime(preview.summary.exportedAt)}")
                    Text("${preview.summary.subjects} 个科目 · ${preview.summary.categories} 个类别 · ${preview.summary.sessions} 条记录")
                    if (preview.summary.prescriptions > 0) {
                        Text("另有 ${preview.summary.prescriptions} 份处方与验证结果。")
                    }
                    if (preview.summary.wrongBatches > 0 || preview.summary.reviewAttempts > 0) {
                        Text(
                            "另有 ${preview.summary.wrongBatches} 个错题袋 · ${preview.summary.reviewAttempts} 轮复做记录（v5 备份）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "恢复会替换本机全部记录，并在恢复前自动备份当前数据。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (preview.preferencesAvailable) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = !busy) { restorePreferences = !restorePreferences }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = restorePreferences,
                                onCheckedChange = { restorePreferences = it },
                                enabled = !busy,
                            )
                            Text("同时恢复偏好设置与当前考试")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = { onConfirmRestore(restorePreferences) }) {
                    Text(if (busy) "正在恢复…" else "恢复")
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = onCancelRestore) { Text("取消") }
            },
        )
    }
    var switching by remember { mutableStateOf(false) }
    if (switching) ExamSwitchSheet(state, onDismiss = { switching = false }) { type ->
        switching = false
        switchExam(type)
    }
    var examName by rememberSaveable(state.exam) { mutableStateOf(state.exam.name) }
    var examDate by rememberSaveable(state.exam) { mutableStateOf(state.exam.examDate?.toString().orEmpty()) }
    // v5：目标分与满分口径成对保存；没有口径就不显示「距目标分」（D22）。
    var targetScoreText by rememberSaveable(state.exam) { mutableStateOf(state.exam.targetScore?.toString().orEmpty()) }
    var targetMaxText by rememberSaveable(state.exam) { mutableStateOf(state.exam.targetMaxScoreTenths?.let(ScoreRules::format) ?: "") }
    var questions by rememberSaveable(state.settings) { mutableStateOf(state.settings.dailyQuestionGoal?.toString().orEmpty()) }
    var minutes by rememberSaveable(state.settings) { mutableStateOf(state.settings.dailyMinuteGoal?.toString().orEmpty()) }
    var selfAssessment by rememberSaveable(state.settings) { mutableStateOf(state.settings.selfAssessment) }
    // 外观是整屏预览，不用 rememberSaveable：离开本页后回来应回到已保存的选择，而不是上次未保存的预览。
    var theme by remember(state.settings) { mutableStateOf(state.settings.theme) }
    var attribution by rememberSaveable(state.settings) { mutableStateOf(state.settings.dateAttribution) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    // 外观选中即整屏预览：背景、底栏与系统栏一起换；离开本页回到已保存的设置。
    DisposableEffect(theme) {
        onPreviewTheme(theme)
        onDispose { onPreviewTheme(null) }
    }
    StudyClockTheme(theme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                PageHeading("按自己的节奏来", "我的学习计划")

                ChapterHead("01", "考试", "科目、类别、记录与统计都按考试分开，互不混算")
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { switching = true },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(state.exam.displayName, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "切换后，两套资料与统计互不影响",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("切换", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                }
                OutlinedTextField(
                    examName,
                    { examName = it },
                    label = { Text("考试名称（选填）") },
                    placeholder = { Text(ExamTarget(state.currentExam).displayName) },
                    singleLine = true,
                    shape = RoundedCornerShape(2.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    examDate,
                    { examDate = it },
                    label = { Text("考试日期（选填）") },
                    placeholder = { Text("例如 2026-12-20") },
                    singleLine = true,
                    shape = RoundedCornerShape(2.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        targetScoreText, { targetScoreText = CountInput.sanitize(it) }, label = { Text("目标分") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, shape = RoundedCornerShape(2.dp), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        targetMaxText, { targetMaxText = sanitizeScore(it) }, label = { Text("对应满分") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true, shape = RoundedCornerShape(2.dp), modifier = Modifier.weight(1f),
                    )
                }
                Disclosure(
                    "计分目标怎么参与统计",
                    "只有计分记录的满分与「对应满分」一致、且最近至少 3 次同口径记录时，看板才显示距目标分；" +
                        "不同满分之间不换算。目标分与对应满分要一起填，或一起留空。",
                )

                ChapterHead("02", "每日目标", "题量与时长可以只填一项；留空即关闭这一项")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        questions, { questions = CountInput.sanitize(it) }, label = { Text("题量") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, shape = RoundedCornerShape(2.dp), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        minutes, { minutes = CountInput.sanitize(it) }, label = { Text("分钟") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, shape = RoundedCornerShape(2.dp), modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("记录难度与精力", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "默认关闭，开启后在结算时选填。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(selfAssessment, { selfAssessment = it }, modifier = Modifier.semantics { contentDescription = "记录难度与精力" })
                }

                ChapterHead("03", "显示", "外观选中即整屏预览，保存后才全局生效")
                RatingRow("外观", listOf("跟随系统", "浅色", "深色"), theme.ordinal) { theme = ThemeMode.entries[it] }
                if (theme != state.settings.theme) {
                    Text(
                        "已按选择预览，点「保存设置」后全局生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                RatingRow("跨零点记录归属", listOf("开始日", "结束日"), attribution.ordinal) { attribution = DateAttribution.entries[it] }

                // 保存属于整页，不属于「三 显示」。补上半个章间距，让它明显落在三章之外。
                Spacer(Modifier.height(14.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    enabled = !busy,
                    shape = RoundedCornerShape(2.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    onClick = {
                        val parsedDate = if (examDate.isBlank()) null else runCatching { LocalDate.parse(examDate.trim()) }.getOrNull()
                        val questionGoal = questions.toIntOrNull()
                        val minuteGoal = minutes.toIntOrNull()
                        val targetScoreValue = targetScoreText.toIntOrNull()
                        val targetMaxValue = ScoreRules.parseTenths(targetMaxText)
                        error = when {
                            examDate.isNotBlank() && parsedDate == null -> "考试日期格式应为 年-月-日。"
                            questions.isNotBlank() && (questionGoal == null || questionGoal <= 0) -> "每日题量应为正整数，或留空。"
                            minutes.isNotBlank() && (minuteGoal == null || minuteGoal <= 0) -> "每日分钟数应为正整数，或留空。"
                            targetScoreText.isNotBlank() != targetMaxText.isNotBlank() -> "目标分与对应满分要一起填，或一起留空。"
                            targetScoreText.isNotBlank() && (targetScoreValue == null || targetScoreValue <= 0) -> "目标分应为正数。"
                            targetMaxText.isNotBlank() && targetMaxValue == null -> "对应满分最多 1 位小数。"
                            targetMaxValue != null && targetScoreValue != null && targetScoreValue * 10 > targetMaxValue -> "目标分不能超过对应满分。"
                            else -> null
                        }
                        if (error == null) save(
                            UserSettings(questionGoal, minuteGoal, selfAssessment, attribution, theme),
                            ExamTarget(
                                type = state.currentExam,
                                name = examName.trim(),
                                examDate = parsedDate,
                                targetScore = targetScoreValue,
                                targetMaxScoreTenths = targetMaxValue,
                            ),
                        )
                    },
                ) { Text(if (busy) "正在保存…" else "保存设置", style = MaterialTheme.typography.titleMedium) }

                ChapterHead("04", "数据", "以下操作点了就执行，不经过上面的保存")
                Text(
                    when {
                        state.backup.lastBackupAt == null -> "尚未备份过；应用私有目录的自动备份不能防丢机。"
                        else -> "上次备份：${formatBackupTime(state.backup.lastBackupAt)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DataRow(
                    title = "导出 CSV",
                    note = "${CsvWriter.columnCount} 列，Excel / WPS 可直接打开",
                    enabled = !busy && state.sessions.isNotEmpty(),
                ) { exportLauncher.launch("题迹练习记录-${LocalDate.now()}.csv") }
                DataRow(
                    title = "备份全部数据",
                    note = "JSON 全量（schema 5），含错题袋与复做轮次；文件未加密",
                    enabled = !busy,
                    action = "备份",
                    disabledAction = "请稍候",
                ) { backupExportLauncher.launch("题迹备份-${LocalDate.now()}.json") }
                DataRow(
                    title = "备份文件夹",
                    note = state.backup.folderName?.let {
                        "已选择「$it」；超过 14 天未备份会自动写入，保留最近 ${FolderBackup.KEEP_FILES} 份"
                    } ?: "选择后可自动写入外部文件夹",
                    enabled = !busy,
                    action = if (state.backup.hasFolder) "更换" else "选择",
                    disabledAction = "请稍候",
                ) { backupFolderLauncher.launch(null) }
                if (state.backup.hasFolder) {
                    DataRow(
                        title = "立即写入文件夹",
                        note = "写一份带时间的 JSON 备份，并更新备份提醒",
                        enabled = !busy,
                        action = "备份",
                        disabledAction = "请稍候",
                    ) { onBackupToFolder() }
                }
                DataRow(
                    title = "从备份恢复",
                    note = "先预览再替换，恢复前自动存一份",
                    enabled = !busy,
                    action = "选择文件",
                    disabledAction = "请稍候",
                ) {
                    backupImportLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                }
                DataRow(
                    title = "导入历史记录",
                    note = "读 20 列或旧 14 列 CSV，先预览列与科目对应",
                    enabled = !busy,
                    action = "导入",
                    disabledAction = "请稍候",
                ) { onOpenImport() }
                DataRow(
                    title = "分类管理",
                    note = "${state.subjects.count { !it.archived }} 个科目 · ${state.categories.count { !it.archived }} 个类别；可改名、排序、归档",
                    enabled = !busy,
                    action = "管理",
                    disabledAction = "请稍候",
                ) { onOpenCatalog() }

                ChapterHead("05", "关于", null)
                Disclosure(
                    "到点提醒怎么工作",
                    "倒计时到点会自动结算并发通知；锁屏与通知栏的显示取决于系统的通知权限。",
                )
                Disclosure(
                    "训练处方怎么用",
                    "在看板按今天的剩余预算生成，一天最多两条；完成后对照前后各 3 次记录，不在样本不足时给结论。",
                )
                Text(
                    "记录只存在这台设备，没有网络权限，也不采集设备信息。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "题迹 ${BuildConfig.VERSION_NAME} · 开发版",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
    }
}

/** 已实现的数据出口：整行可点，右侧给出结果反馈的位置（后续可换成“上次导出时间”）。 */
@Composable
private fun DataRow(
    title: String,
    note: String,
    enabled: Boolean,
    action: String = "导出",
    disabledAction: String = "无记录",
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            if (enabled) action else disabledAction,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary.copy(alpha = if (enabled) 1f else 0.4f),
        )
    }
}

private fun formatBackupTime(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

private val IMPORT_MIME_TYPES = arrayOf(
    "text/csv",
    "text/comma-separated-values",
    "text/plain",
    "application/csv",
    "application/vnd.ms-excel",
    "application/octet-stream",
)

/** 导入分两步：先选文件得到只读预览，确认后才在一个事务里写入，并先自动备份。 */
@Composable
private fun ImportScreen(
    state: AppState,
    busy: Boolean,
    preview: CsvImportPreview?,
    onPickFile: (android.net.Uri) -> Unit,
    onRemap: (ImportField, Int?) -> Unit,
    onResolve: (String, ImportResolution) -> Unit,
    onSkipDuplicates: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onPickFile)
    }
    // 离开导入页就丢掉预览；重新进入也先清干净，避免对已经过期的文件点“确认”。
    LaunchedEffect(Unit) { onCancel() }
    DisposableEffect(Unit) { onDispose { onCancel() } }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageHeading("可从导出的文件开始", "导入练习记录")
        Text(
            "读取题迹导出的 CSV，也支持列名相同的自建表格。文件需为 UTF-8 编码，单次最多 1 万行。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { picker.launch(IMPORT_MIME_TYPES) },
            enabled = !busy,
            shape = RoundedCornerShape(2.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            Text(
                when {
                    preview != null -> "换一个文件"
                    busy -> "正在读取…"
                    else -> "选择文件"
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
        preview?.let {
            ImportPreviewBody(
                state, busy, it,
                onRemap = onRemap,
                onResolve = onResolve,
                onSkipDuplicates = onSkipDuplicates,
                onConfirm = onConfirm,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportPreviewBody(
    state: AppState,
    busy: Boolean,
    preview: CsvImportPreview,
    onRemap: (ImportField, Int?) -> Unit,
    onResolve: (String, ImportResolution) -> Unit,
    onSkipDuplicates: (Boolean) -> Unit,
    onConfirm: () -> Unit,
) {
    val scan = preview.scan
    Text(preview.fileName, style = MaterialTheme.typography.titleMedium)
    Text(
        "共 ${scan.totalDataRows} 行 · 可导入 ${preview.importableCount} 条 · 无法解析 ${scan.errors.size} 行",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (scan.missingRequired.isNotEmpty()) {
        Text(
            "还缺必填列：${scan.missingRequired.joinToString("、") { it.label }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    SectionLabel("列对应")
    Text(
        "已按列名自动识别；表头不同的话，在这里手动指定。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    IMPORT_FIELDS.forEach { (field, note) ->
        ImportMappingRow(
            field = field,
            note = note,
            header = scan.headers,
            columns = scan.mapping.columns,
            enabled = !busy,
            onSelect = { index -> onRemap(field, index) },
        )
    }

    SectionLabel("前 ${preview.samples.size} 行预览")
    if (preview.samples.isEmpty()) {
        Text("没有可展示的有效行。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    preview.samples.forEach { candidate ->
        ImportSampleRow(candidate)
    }

    if (scan.errors.isNotEmpty()) {
        SectionLabel("无法导入的行")
        Text(
            "整批不会写入，请修正后重新选择文件。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        scan.errors.take(6).forEach { error ->
            Text(
                "第 ${error.line} 行：${error.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (scan.errors.size > 6) {
            Text(
                "另有 ${scan.errors.size - 6} 行同类问题。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (preview.knownPairs.isNotEmpty()) {
        SectionLabel("已匹配的科目与类别")
        preview.knownPairs.forEach { pair ->
            ImportKnownPairRow(state, pair)
        }
    }

    if (preview.unknownPairs.isNotEmpty()) {
        SectionLabel("未匹配的科目与类别")
        Text(
            "这些组合在当前考试里还不存在，逐项决定怎么处理。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        preview.unknownPairs.forEach { pair ->
            ImportUnknownPairRow(
                state = state,
                pair = pair,
                resolution = preview.resolutions[pair.key] ?: ImportResolution.Skip,
                enabled = !busy,
                onResolve = onResolve,
            )
        }
    }

    if (preview.duplicateLines.isNotEmpty()) {
        SectionLabel("重复检查")
        Row(
            Modifier.fillMaxWidth().clickable(enabled = !busy) { onSkipDuplicates(!preview.skipDuplicates) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(
                checked = preview.skipDuplicates,
                onCheckedChange = { onSkipDuplicates(it) },
                enabled = !busy,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("跳过疑似重复的 ${preview.duplicateLines.size} 条", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "同类别、同开始时刻、同题量视为同一条。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    Text(
        "写入前会自动备份当前数据；导入在一个事务里完成，中途出错不会留下半批记录。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = onConfirm,
        enabled = !busy && scan.missingRequired.isEmpty() && scan.errors.isEmpty() && preview.importableCount > 0,
        shape = RoundedCornerShape(2.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) {
        Text(
            when {
                busy -> "正在导入…"
                preview.importableCount > 0 -> "确认导入 ${preview.importableCount} 条记录"
                else -> "没有可导入的记录"
            },
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

private val IMPORT_FIELDS = listOf(
    ImportField.DATE to "必填，如 2026-10-29",
    ImportField.SUBJECT to "必填",
    ImportField.CATEGORY to "必填",
    ImportField.TOTAL to "必填，整数",
    ImportField.CORRECT to "与错题数填一个即可",
    ImportField.WRONG to "选填",
    ImportField.TIME to "选填，如 09:00-09:25",
    ImportField.DURATION_SECONDS to "选填，秒",
    ImportField.DURATION_TEXT to "选填，如 25:30",
    ImportField.SEQUENCE to "选填，0/1 序列",
    ImportField.DIFFICULTY to "选填，易/中/难",
    ImportField.ENERGY to "选填，低/中/高",
    ImportField.TAGS to "选填，顿号或竖线分隔",
)

@Composable
private fun ImportMappingRow(
    field: ImportField,
    note: String,
    header: List<String>,
    columns: Map<ImportField, Int>,
    enabled: Boolean,
    onSelect: (Int?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = columns[field]
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { expanded = true }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(field.label, style = MaterialTheme.typography.titleSmall)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            Text(
                selected?.let { header.getOrNull(it)?.ifBlank { "第 ${it + 1} 列" } ?: "第 ${it + 1} 列" } ?: "未对应",
                style = MaterialTheme.typography.labelLarge,
                color = if (selected == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.secondary,
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("不导入这一列") },
                    onClick = {
                        expanded = false
                        onSelect(null)
                    },
                )
                header.forEachIndexed { index, title ->
                    DropdownMenuItem(
                        text = { Text(title.ifBlank { "第 ${index + 1} 列" }) },
                        onClick = {
                            expanded = false
                            onSelect(index)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportSampleRow(candidate: ImportCandidate) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "${candidate.date} ${formatImportRange(candidate)} · ${candidate.subjectName} · ${candidate.categoryName}",
            style = MaterialTheme.typography.bodyMedium,
        )
        val duration = candidate.durationSec?.let { " · 净时长 ${it / 60} 分 ${it % 60} 秒" }.orEmpty()
        val accuracy = if (candidate.total > 0) candidate.correct.toDouble() / candidate.total else null
        Text(
            "共 ${candidate.total} 题 · 答对 ${candidate.correct} 题 · 正确率 ${accuracyText(accuracy)}$duration",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val tags = TagRules.parse(candidate.wrongTags)
        if (tags.isNotEmpty()) {
            Text(
                "关键词 ${tags.joinToString(" · ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatImportRange(candidate: ImportCandidate): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(candidate.startAt).atZone(zone).toLocalTime()
    val end = candidate.endAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
    val formatter = DateTimeFormatter.ofPattern("HH:mm")
    return if (end == null) start.format(formatter) else "${start.format(formatter)}–${end.format(formatter)}"
}

@Composable
private fun ImportKnownPairRow(state: AppState, pair: ImportPairSummary) {
    val subjectNames = remember(state.subjects) { state.subjects.associate { it.id to it.name } }
    val label = pair.categoryId?.let { id ->
        state.categories.find { it.id == id }?.let { category ->
            "${subjectNames[category.subjectId] ?: "已归档科目"} · ${category.name}"
        }
    } ?: "现有类别"
    Text(
        "${pair.subjectName} · ${pair.categoryName} · ${pair.rowCount} 行 → $label",
        style = MaterialTheme.typography.bodySmall,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportUnknownPairRow(
    state: AppState,
    pair: ImportPairSummary,
    resolution: ImportResolution,
    enabled: Boolean,
    onResolve: (String, ImportResolution) -> Unit,
) {
    var existingMenu by remember { mutableStateOf(false) }
    val subjectNames = remember(state.subjects) { state.subjects.associate { it.id to it.name } }
    val existingId = (resolution as? ImportResolution.Existing)?.categoryId
    val existingLabel = existingId?.let { id ->
        state.categories.find { it.id == id }?.let { category ->
            "${subjectNames[category.subjectId] ?: "已归档科目"} · ${category.name}"
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${pair.subjectName} · ${pair.categoryName}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${pair.rowCount} 行", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = resolution is ImportResolution.Skip,
                onClick = { onResolve(pair.key, ImportResolution.Skip) },
                label = { Text("跳过") },
                enabled = enabled,
                shape = RoundedCornerShape(2.dp),
                colors = inkChipColors(),
            )
            FilterChip(
                selected = resolution is ImportResolution.Create,
                onClick = { onResolve(pair.key, ImportResolution.Create) },
                label = { Text("新建类别") },
                enabled = enabled,
                shape = RoundedCornerShape(2.dp),
                colors = inkChipColors(),
            )
            Box {
                FilterChip(
                    selected = existingId != null,
                    onClick = { existingMenu = true },
                    label = { Text("映射到现有") },
                    enabled = enabled && state.categories.isNotEmpty(),
                    shape = RoundedCornerShape(2.dp),
                    colors = inkChipColors(),
                )
                DropdownMenu(expanded = existingMenu, onDismissRequest = { existingMenu = false }) {
                    state.categories.forEach { category ->
                        DropdownMenuItem(
                            text = { Text("${subjectNames[category.subjectId] ?: "已归档科目"} · ${category.name}") },
                            onClick = {
                                existingMenu = false
                                onResolve(pair.key, ImportResolution.Existing(category.id))
                            },
                        )
                    }
                }
            }
        }
        existingLabel?.let {
            Text("将写入：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}
