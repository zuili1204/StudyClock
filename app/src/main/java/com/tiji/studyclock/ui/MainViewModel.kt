package com.tiji.studyclock.ui

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tiji.studyclock.BuildConfig
import com.tiji.studyclock.data.BootIdentity
import com.tiji.studyclock.data.backup.BackupCodec
import com.tiji.studyclock.data.backup.BackupPayload
import com.tiji.studyclock.data.backup.BackupRepository
import com.tiji.studyclock.data.backup.BackupSummary
import com.tiji.studyclock.data.backup.BackupValidator
import com.tiji.studyclock.data.backup.FolderBackup
import com.tiji.studyclock.data.export.CsvWriter
import com.tiji.studyclock.data.export.ReportBuilder
import com.tiji.studyclock.data.importer.CsvImport
import com.tiji.studyclock.data.importer.CsvTable
import com.tiji.studyclock.data.importer.ImportCandidate
import com.tiji.studyclock.data.importer.ImportField
import com.tiji.studyclock.data.importer.ImportMapping
import com.tiji.studyclock.data.importer.ImportPair
import com.tiji.studyclock.data.importer.ImportPlan
import com.tiji.studyclock.data.importer.ImportRepository
import com.tiji.studyclock.data.importer.ImportResolution
import com.tiji.studyclock.data.importer.ImportScan
import com.tiji.studyclock.data.repo.BackupSettings
import com.tiji.studyclock.data.repo.CategoryRepository
import com.tiji.studyclock.data.repo.ExamRepository
import com.tiji.studyclock.data.repo.PrescriptionRepository
import com.tiji.studyclock.data.repo.SessionRepository
import com.tiji.studyclock.data.repo.SettingsRepository
import com.tiji.studyclock.data.repo.WrongBatchRepository
import com.tiji.studyclock.data.repo.BackfillPreview
import com.tiji.studyclock.domain.backup.BackupReminder
import com.tiji.studyclock.domain.diagnose.DiagnosisGenerator
import com.tiji.studyclock.domain.diagnose.Finding
import com.tiji.studyclock.domain.diagnose.Insight
import com.tiji.studyclock.domain.diagnose.InsightGenerator
import com.tiji.studyclock.domain.model.Category
import com.tiji.studyclock.domain.model.DateAttribution
import com.tiji.studyclock.domain.model.ExamTarget
import com.tiji.studyclock.domain.model.ExamType
import com.tiji.studyclock.domain.model.NameRules
import com.tiji.studyclock.domain.model.Prescription
import com.tiji.studyclock.domain.model.PrescriptionResult
import com.tiji.studyclock.domain.model.PrescriptionStatus
import com.tiji.studyclock.domain.model.RecordMode
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionKind
import com.tiji.studyclock.domain.model.SessionSource
import com.tiji.studyclock.domain.model.Subject
import com.tiji.studyclock.domain.model.UserSettings
import com.tiji.studyclock.domain.model.WrongBatch
import com.tiji.studyclock.domain.model.ReviewAttempt
import com.tiji.studyclock.domain.model.ScoreRules
import com.tiji.studyclock.domain.prescribe.PrescriptionPlanner
import com.tiji.studyclock.domain.stats.Consistency
import com.tiji.studyclock.domain.stats.CrossModeStats
import com.tiji.studyclock.domain.stats.FocusSnapshot
import com.tiji.studyclock.domain.stats.FocusStats
import com.tiji.studyclock.domain.stats.ModeStructure
import com.tiji.studyclock.domain.stats.RedoLedger
import com.tiji.studyclock.domain.stats.RedoMetrics
import com.tiji.studyclock.domain.stats.ScoreEngine
import com.tiji.studyclock.domain.stats.ScoreSnapshot
import com.tiji.studyclock.domain.stats.StatsEngine
import com.tiji.studyclock.domain.stats.TrackComparison
import com.tiji.studyclock.domain.stats.StreakSummary
import com.tiji.studyclock.domain.stats.StatsSnapshot
import com.tiji.studyclock.domain.stats.TagStat
import com.tiji.studyclock.domain.stats.TagStats
import com.tiji.studyclock.domain.timer.FinishedTimer
import com.tiji.studyclock.domain.timer.TimerController
import com.tiji.studyclock.domain.timer.TimerStart
import com.tiji.studyclock.service.TimerService
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppState(
    val loading: Boolean = true,
    val subjects: List<Subject> = emptyList(),
    val categories: List<Category> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val settings: UserSettings = UserSettings(),
    val exam: ExamTarget = ExamTarget(),
    val stats: StatsSnapshot? = null,
    val todayStats: StatsSnapshot? = null,
    val findings: List<Finding> = emptyList(),
    val error: String? = null,
    val today: LocalDate = LocalDate.now(),
    /** 当前选中的考试类型；科目、类别、记录、统计都按它隔离。 */
    val currentExam: ExamType = ExamType.CIVIL_SERVANT,
    /** 所有考试类型的计划（含未选中的），用于切换面板与统计汇总。 */
    val examPlans: List<ExamTarget> = emptyList(),
    /** 各考试类型的记录数与计划，供切换面板使用。 */
    val examSummaries: List<ExamSummary> = emptyList(),
    /** 当前考试的全部记录（不受“待撤销删除”影响时用于切换面板统计）。 */
    val examSessions: List<Session> = emptyList(),
    /** 当前考试的打卡连续性。 */
    val streak: StreakSummary? = null,
    /** 由规则生成的“发现”，按关注度排序。 */
    val insights: List<Insight> = emptyList(),
    /** 当前考试的客观题错因聚合，按出现次数排序。 */
    val tagStats: List<TagStat> = emptyList(),
    /** v5：计分 / 复做关键词单独成组，不混进客观题词云（§5.7）。 */
    val otherTagStats: List<TagStat> = emptyList(),
    /** 最近常用的关键词，用于记录页快速选择。 */
    val recentTags: List<String> = emptyList(),
    /** 当前考试的处方，按生成时间倒序；进行中的排在最前由界面决定。 */
    val prescriptions: List<Prescription> = emptyList(),
    /** 已冻结的验证结果，按处方 ID 索引；样本不足的结果不落库。 */
    val prescriptionResults: Map<Long, PrescriptionResult> = emptyMap(),
    /** 上次用过的自定义倒计时分钟数，用于计时页预填。 */
    val customTimerMinutes: Int? = null,
    /** 备份状态与提醒；提醒只在应用打开或跨日时重算。 */
    val backup: BackupInfo = BackupInfo(),
    // ---- v5：三轨联动 ----
    val wrongBatches: List<WrongBatch> = emptyList(),
    val reviewAttempts: List<ReviewAttempt> = emptyList(),
    val scoreStats: ScoreSnapshot? = null,
    /** v0.11：专注只计投入，与三条表现轨并列展示但不合并。 */
    val focusStats: FocusSnapshot? = null,
    val redo: RedoMetrics? = null,
    val modeStructure: ModeStructure? = null,
    val trackComparisons: List<TrackComparison> = emptyList(),
    /** 从错题袋详情发起的复做上下文；计时结算时写入 batchId，保存后清空。 */
    val redoBatchId: Long? = null,
)

data class BackupInfo(
    val lastBackupAt: Long? = null,
    val folderUri: String? = null,
    val folderName: String? = null,
    val hasFolder: Boolean = false,
    val reminderDismissedAt: Long? = null,
    val reminderDue: Boolean = false,
    val reminderNever: Boolean = false,
    val daysSinceBackup: Long? = null,
)

data class ExamSummary(
    val type: ExamType,
    val name: String,
    val examDate: LocalDate?,
    val sessionCount: Int,
    val questionCount: Int,
)

data class PendingDeletion(val sessionId: Long, val deadlineMillis: Long)

/** 记录页删除的撤销窗口；开发规划 S6.0-4 定为 8–10 秒。 */
private const val UNDO_WINDOW_MS = 10_000L

/** 合并层只负责把偏好、计划与处方拼在一起，避免组合器参数超过上限。 */
private data class PlansSnapshot(
    val settings: UserSettings,
    val currentExam: ExamType,
    val customTimerMinutes: Int?,
    val backup: BackupSettings,
    val plans: List<ExamTarget>,
    val prescriptions: List<Prescription>,
    val results: List<PrescriptionResult>,
)

private data class SettingsSnapshot(
    val settings: UserSettings,
    val currentExam: ExamType,
    val customTimerMinutes: Int?,
    val backup: BackupSettings,
)

/** 导入预览只暴露数量与开关，原始备份留在 ViewModel，避免界面持有整库数据。 */
data class RestorePreview(
    val summary: BackupSummary,
    val preferencesAvailable: Boolean,
)

/** 导入预览只暴露摘要与逐行结果，原始文件内容留在 ViewModel。 */
data class ImportPairSummary(
    val key: String,
    val subjectName: String,
    val categoryName: String,
    val rowCount: Int,
    /** 已匹配或用户指定的现有类别；待新建与跳过时为 null。 */
    val categoryId: Long? = null,
)

data class CsvImportPreview(
    val fileName: String,
    val scan: ImportScan,
    val unknownPairs: List<ImportPairSummary>,
    val knownPairs: List<ImportPairSummary> = emptyList(),
    val knownPairCategoryIds: Map<String, Long> = emptyMap(),
    val resolutions: Map<String, ImportResolution> = emptyMap(),
    val duplicateLines: Set<Int> = emptySet(),
    val skipDuplicates: Boolean = true,
) {
    val samples: List<ImportCandidate> get() = scan.candidates.take(CsvImport.PREVIEW_ROWS)
    val importableCount: Int
        get() = scan.candidates.count { candidate ->
            val key = ImportPair(candidate.subjectName, candidate.categoryName).key
            val mapped = key in knownPairCategoryIds || (resolutions[key] ?: ImportResolution.Skip) != ImportResolution.Skip
            mapped && !(skipDuplicates && candidate.line in duplicateLines)
        }
}

sealed interface UiEvent {
    data object RecordSaved : UiEvent
    data class Message(val text: String) : UiEvent
    data class Imported(val text: String) : UiEvent
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository,
    private val examRepository: ExamRepository,
    private val timerController: TimerController,
    private val backupRepository: BackupRepository,
    private val folderBackup: FolderBackup,
    private val importRepository: ImportRepository,
    private val prescriptionRepository: PrescriptionRepository,
    private val wrongBatchRepository: WrongBatchRepository,
    bootIdentity: BootIdentity,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {
    val bootCount: Int = bootIdentity.current
    private val eventChannel = Channel<UiEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    private val pendingDelete = MutableStateFlow<PendingDeletion?>(null)
    val pendingDeletion = pendingDelete.asStateFlow()
    val activeTimer: StateFlow<TimerStart?> = timerController.active
    val finishedTimer: StateFlow<FinishedTimer?> = timerController.finished
    private var deleteJob: Job? = null
    private var dateJob: Job? = null
    private val today = MutableStateFlow(LocalDate.now())
    private val reloadSignal = MutableStateFlow(0)
    /** v5：从错题袋详情发起的复做上下文；结算保存后清空。 */
    private val redoTarget = MutableStateFlow<Long?>(null)
    val busy = MutableStateFlow(false)
    private val restorePreviewState = MutableStateFlow<RestorePreview?>(null)
    val restorePreview = restorePreviewState.asStateFlow()
    private var pendingRestorePayload: BackupPayload? = null
    private val importPreviewState = MutableStateFlow<CsvImportPreview?>(null)
    val importPreview = importPreviewState.asStateFlow()
    private var importTable: CsvTable? = null

    init {
        refreshDate()
        viewModelScope.launch {
            timerController.errors.collect { eventChannel.send(UiEvent.Message(it)) }
        }
        // 到点未开始的处方随日期滚动失效；同一天内重复收集不会改任何行。
        viewModelScope.launch {
            today.collect {
                runCatching { prescriptionRepository.expireOverdue() }
            }
        }
        // 完成后样本补足时再冻结验证结果；记录或处方任一变化都重算一次，结论不足时不写库。
        viewModelScope.launch {
            combine(sessionRepository.sessions, prescriptionRepository.prescriptions) { _, rows ->
                rows.filter { it.status == PrescriptionStatus.DONE }.map { it.id }
            }.collect { ids ->
                ids.forEach { id ->
                    runCatching { prescriptionRepository.evaluateAndRecord(id) }
                }
            }
        }
        // 只要本机存在进行中的计时，就把前台服务接回来；结算为空时服务会自行退出。
        viewModelScope.launch {
            timerController.active
                .map { it != null }
                .distinctUntilChanged()
                .collect { running -> if (running) TimerService.start(appContext) }
        }
        // 打开应用时补一次外部文件夹备份；没有选择文件夹或未到期就跳过，不做后台定时任务。
        viewModelScope.launch {
            val settings = settingsRepository.backupSettings.first()
            val folder = settings.folderUri ?: return@launch
            val oldest = runCatching { sessionRepository.sessions.first().minOfOrNull { it.startAt } }.getOrNull()
            val reminder = BackupReminder.evaluate(
                lastBackupAt = settings.lastBackupAt,
                dismissedAt = settings.reminderDismissedAt,
                oldestSessionAt = oldest,
                now = System.currentTimeMillis(),
            )
            if (reminder.due) writeFolderBackup(folder, silent = true)
        }
    }

    private val settingsAndExam = combine(
        settingsRepository.settings,
        settingsRepository.currentExam,
        settingsRepository.customTimerMinutes,
        settingsRepository.backupSettings,
    ) { settings, exam, customTimerMinutes, backup ->
        SettingsSnapshot(settings, exam, customTimerMinutes, backup)
    }

    private val catalog = combine(
        categoryRepository.subjects,
        categoryRepository.categories,
        sessionRepository.sessions,
    ) { subjects, categories, sessions -> Triple(subjects, categories, sessions) }

    private val plansAndPrescriptions = combine(
        settingsAndExam,
        examRepository.plans,
        prescriptionRepository.prescriptions,
        prescriptionRepository.results,
    ) { settings, plans, prescriptions, results ->
        PlansSnapshot(
            settings = settings.settings,
            currentExam = settings.currentExam,
            customTimerMinutes = settings.customTimerMinutes,
            backup = settings.backup,
            plans = plans,
            prescriptions = prescriptions,
            results = results,
        )
    }

    private val batchSnapshot = combine(
        wrongBatchRepository.batches,
        wrongBatchRepository.attempts,
    ) { batches, attempts -> batches to attempts }

    private val data = combine(catalog, plansAndPrescriptions, batchSnapshot, redoTarget) { (subjects, categories, sessions), extra, batchData, redoId ->
        val (batches, attempts) = batchData
        AppState(
            loading = false, subjects = subjects, categories = categories, sessions = sessions,
            settings = extra.settings,
            exam = extra.plans.firstOrNull { it.type == extra.currentExam } ?: ExamTarget(type = extra.currentExam),
            currentExam = extra.currentExam, examPlans = extra.plans, examSummaries = emptyList(),
            prescriptions = extra.prescriptions,
            prescriptionResults = extra.results.associateBy { it.prescriptionId },
            customTimerMinutes = extra.customTimerMinutes,
            wrongBatches = batches,
            reviewAttempts = attempts,
            redoBatchId = redoId,
            backup = BackupInfo(
                lastBackupAt = extra.backup.lastBackupAt,
                folderUri = extra.backup.folderUri,
                folderName = extra.backup.folderName,
                hasFolder = extra.backup.folderUri != null,
                reminderDismissedAt = extra.backup.reminderDismissedAt,
            ),
        )
    }

    val state = combine(data, pendingDelete, today) { base, pending, date ->
        val examSessions = base.sessions.filter { it.examType == base.currentExam }
        val visible = examSessions.filterNot { it.id == pending?.sessionId }.sortedByDescending { it.startAt }
        val subjects = base.subjects.filter { it.examType == base.currentExam }.sortedBy { it.sortOrder }
        val subjectIds = subjects.map { it.id }.toSet()
        val categories = base.categories.filter { it.subjectId in subjectIds }
        val snapshot = StatsEngine.calculate(visible, categories, date, attribution = base.settings.dateAttribution)
        val todaySessions = visible.filter { sessionDate(it, base.settings.dateAttribution) == date }
        val streak = Consistency.summarize(visible, date, base.settings.dateAttribution)
        val baseInsights = InsightGenerator.generate(
            sessions = visible,
            categories = categories,
            subjects = subjects,
            exam = base.exam,
            hourStats = snapshot.hourStats,
            streak = streak,
            today = date,
            attribution = base.settings.dateAttribution,
            dailyQuestionGoal = base.settings.dailyQuestionGoal,
            max = Int.MAX_VALUE,
        )
        // v5：当前考试的错题袋与轮次；其它考试的袋不进本考试的统计。
        val examBatches = base.wrongBatches.filter { it.examType == base.currentExam }
        val batchIds = examBatches.map { it.id }.toSet()
        val examAttempts = base.reviewAttempts.filter { it.batchId in batchIds }
        val scoreStats = ScoreEngine.calculate(visible, date, attribution = base.settings.dateAttribution)
        val focusStats = FocusStats.calculate(visible, date, attribution = base.settings.dateAttribution)
        val redo = RedoLedger.metrics(examBatches, examAttempts, date)
        val modeStructure = CrossModeStats.structure(visible)
        val comparisons = CrossModeStats.compareByCategory(
            visible, categories, snapshot, scoreStats, examBatches, examAttempts, date,
        )
        val crossInsights = InsightGenerator.generateCrossMode(
            sessions = visible,
            categories = categories,
            score = scoreStats,
            redo = redo,
            batches = examBatches,
            attempts = examAttempts,
            today = date,
            attribution = base.settings.dateAttribution,
        )
        val insights = (baseInsights + crossInsights)
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.tone.ordinal }, { it.id }))
            .take(InsightGenerator.MAX_INSIGHTS)
        // 词云只吃客观题错因；快速复用仍看全部最近写过的关键词。
        val tagStats = TagStats.summarizeObjective(visible, base.settings.dateAttribution)
        val otherTagStats = TagStats.summarizeOther(visible, base.settings.dateAttribution)
        val recentTags = TagStats.recent(TagStats.summarize(visible, base.settings.dateAttribution), 8).map { it.tag }
        val categoryIds = categories.map { it.id }.toSet()
        val prescriptions = base.prescriptions.filter { it.categoryId in categoryIds }
        val prescriptionResults = base.prescriptionResults.filterKeys { id -> prescriptions.any { it.id == id } }
        val reminder = BackupReminder.evaluate(
            lastBackupAt = base.backup.lastBackupAt,
            dismissedAt = base.backup.reminderDismissedAt,
            oldestSessionAt = base.sessions.minOfOrNull { it.startAt },
            now = System.currentTimeMillis(),
        )
        val summaries = ExamType.entries.map { type ->
            val rows = base.sessions.filter { it.examType == type }
            val plan = base.examPlans.firstOrNull { it.type == type }
            ExamSummary(
                type = type,
                name = plan?.displayName ?: ExamTarget(type = type).displayName,
                examDate = plan?.examDate,
                sessionCount = rows.size,
                questionCount = rows.sumOf { it.totalCount },
            )
        }
        base.copy(
            today = date,
            subjects = subjects,
            categories = categories,
            sessions = visible,
            examSessions = examSessions,
            examSummaries = summaries,
            stats = snapshot,
            todayStats = StatsEngine.calculate(todaySessions, categories, date, attribution = base.settings.dateAttribution),
            findings = DiagnosisGenerator.generate(snapshot, categories),
            streak = streak,
            insights = insights,
            tagStats = tagStats,
            otherTagStats = otherTagStats,
            recentTags = recentTags,
            scoreStats = scoreStats,
            focusStats = focusStats,
            redo = redo,
            modeStructure = modeStructure,
            trackComparisons = comparisons,
            prescriptions = prescriptions,
            prescriptionResults = prescriptionResults,
            backup = base.backup.copy(
                reminderDue = reminder.due,
                reminderNever = reminder.neverBackedUp,
                daysSinceBackup = reminder.daysSince,
            ),
        )
    }.flowOn(Dispatchers.Default).retryWhen { _, _ ->
        reloadSignal.drop(1).first()
        true
    }.catch {
        emit(AppState(loading = false, error = "暂时无法读取本机记录。已有数据不会被清除。"))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppState())

    /** 读取失败后由界面触发重订阅；已保存的数据不会因此改动。 */
    fun retryLoad() {
        reloadSignal.value += 1
    }

    fun refreshDate() {
        today.value = LocalDate.now()
        dateJob?.cancel()
        dateJob = viewModelScope.launch {
            while (true) {
                val now = ZonedDateTime.now()
                val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
                delay(Duration.between(now, midnight).toMillis().coerceAtLeast(1))
                today.value = LocalDate.now()
            }
        }
    }

    fun startTimer(subjectId: Long, categoryId: Long, targetSec: Int? = null) {
        viewModelScope.launch {
            try {
                timerController.start(subjectId, categoryId, targetSec)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("计时未能开始，请重试。"))
            }
        }
    }

    /** v0.11：专注计时复用同一状态机，只多一个 focus 标记。 */
    fun startFocus(subjectId: Long, categoryId: Long, targetSec: Int? = null) {
        viewModelScope.launch {
            try {
                timerController.start(subjectId, categoryId, targetSec, focus = true)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("专注计时未能开始，请重试。"))
            }
        }
    }

    fun pauseTimer() {
        viewModelScope.launch {
            try {
                timerController.pause()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("暂停未能保存，计时仍在继续。"))
            }
        }
    }

    fun resumeTimer() {
        viewModelScope.launch {
            try {
                timerController.resume()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("继续未能保存，请重试。"))
            }
        }
    }

    fun endTimer() {
        viewModelScope.launch {
            try {
                timerController.finish()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("结束未能保存，请重试。"))
            }
        }
    }

    fun discardTimer() {
        // 放弃的计时可能来自处方；先记下类别，清除计时失败时不动处方。
        val categoryId = activeTimer.value?.categoryId ?: finishedTimer.value?.categoryId
        viewModelScope.launch {
            try {
                val cleared = timerController.discard()
                redoTarget.value = null
                if (cleared && categoryId != null) {
                    prescriptionRepository.revertStarted(categoryId)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("计时状态未能写入本机。"))
            }
        }
    }

    /** 通知权限被拒时计时照常进行，只提示锁屏与通知栏将看不到进度。 */
    fun reportNotificationPermissionDenied() {
        viewModelScope.launch {
            eventChannel.send(
                UiEvent.Message("未开启通知权限，锁屏和通知栏不会显示计时进度；计时仍会继续，可在系统设置中开启通知。"),
            )
        }
    }

    fun saveSession(session: Session) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                // originDate 必须按用户设置的日期归属折算，否则 END 归属下错题袋与日历会错一天。
                sessionRepository.save(session, state.value.settings.dateAttribution)
                if (redoTarget.value != null) redoTarget.value = null
                val newTimerResult = session.source == SessionSource.TIMER && session.id == 0L
                eventChannel.send(UiEvent.RecordSaved)
                if (newTimerResult) {
                    try {
                        timerController.clearResult()
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: Exception) {
                        eventChannel.send(UiEvent.Message("记录已保存，但计时结果未能清除；如再次看到本次结果，请直接放弃。"))
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("保存未成功，请检查输入后重试；填写内容已保留。"))
            } finally {
                busy.value = false
            }
        }
    }

    fun saveGoals(settings: UserSettings, exam: ExamTarget) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                examRepository.save(exam)
                settingsRepository.save(settings)
                eventChannel.send(UiEvent.Message("设置已保存"))
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("设置未能全部保存，请重试。"))
            } finally {
                busy.value = false
            }
        }
    }

    fun deleteSession(session: Session) {
        if (pendingDelete.value != null) return
        pendingDelete.value = PendingDeletion(session.id, SystemClock.elapsedRealtime() + UNDO_WINDOW_MS)
        deleteJob = viewModelScope.launch {
            try {
                delay(UNDO_WINDOW_MS)
                sessionRepository.delete(session)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("删除未成功，记录已保留。"))
            } finally {
                pendingDelete.value = null
            }
        }
    }

    fun undoDelete() {
        deleteJob?.cancel()
        pendingDelete.value = null
    }

    // ---- v5：计分、错题复做与错题袋的界面入口 ----

    /** 从错题袋详情发起复做：自动带出科目/类别并开计时，结算表单据此挂接袋 ID。 */
    fun startRedo(batchId: Long, onStarted: () -> Unit) {
        if (busy.value) return
        if (activeTimer.value != null || finishedTimer.value != null) {
            viewModelScope.launch { eventChannel.send(UiEvent.Message("先结束当前计时或填写待结算记录，再开始复做。")) }
            return
        }
        busy.value = true
        viewModelScope.launch {
            try {
                val batch = wrongBatchRepository.batch(batchId)
                if (batch == null) {
                    eventChannel.send(UiEvent.Message("这个错题袋已不存在。"))
                    return@launch
                }
                if (wrongBatchRepository.remaining(batchId) <= 0) {
                    eventChannel.send(UiEvent.Message("这个错题袋已经消化完了，可以归档它。"))
                    return@launch
                }
                redoTarget.value = batchId
                val started = timerController.start(batch.subjectId, batch.categoryId, null)
                if (started == null) {
                    redoTarget.value = null
                    eventChannel.send(UiEvent.Message("计时未能开始，请重试。"))
                } else {
                    onStarted()
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                redoTarget.value = null
                eventChannel.send(UiEvent.Message("复做未能开始，请重试。"))
            } finally {
                busy.value = false
            }
        }
    }

    fun cancelRedo() {
        redoTarget.value = null
    }

    /** 类别默认记录模式只影响结算页预填，不改写历史记录（D24）。 */
    fun setCategoryDefaultMode(categoryId: Long, mode: RecordMode) {
        viewModelScope.launch {
            try {
                categoryRepository.setDefaultMode(categoryId, mode)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("默认记录方式未能记住，下次再选一次即可。"))
            }
        }
    }

    /** 按类别记忆常用满分；下次计分结算自动预填。 */
    fun rememberMaxScore(categoryId: Long, maxScoreTenths: Int?) {
        viewModelScope.launch {
            try {
                categoryRepository.rememberMaxScore(categoryId, maxScoreTenths)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IllegalArgumentException) {
                eventChannel.send(UiEvent.Message(exception.message ?: "满分不合法。"))
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("满分未能记住，下次再填一次即可。"))
            }
        }
    }

    fun setBatchArchived(batchId: Long, archived: Boolean) {
        viewModelScope.launch {
            try {
                wrongBatchRepository.setArchived(batchId, archived)
                eventChannel.send(UiEvent.Message(if (archived) "错题袋已归档" else "错题袋已取消归档"))
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("归档状态未能保存，请重试。"))
            }
        }
    }

    fun setBatchNote(batchId: Long, note: String?) {
        viewModelScope.launch {
            try {
                wrongBatchRepository.setNote(batchId, note)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IllegalArgumentException) {
                eventChannel.send(UiEvent.Message(exception.message ?: "备注过长。"))
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("备注未能保存，请重试。"))
            }
        }
    }

    private val backfillPreviewState = MutableStateFlow<BackfillPreview?>(null)
    val backfillPreview = backfillPreviewState.asStateFlow()

    /** D18：历史错题从不自动回填，必须由用户在这里显式确认。 */
    fun showBackfillPreview() {
        viewModelScope.launch {
            try {
                val snapshot = state.value
                backfillPreviewState.value = wrongBatchRepository.previewBackfill(
                    sessions = snapshot.examSessions,
                    from = null, to = null, categoryIds = null,
                    attribution = snapshot.settings.dateAttribution,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("历史错题预览未能读取，原数据未改动。"))
            }
        }
    }

    fun dismissBackfillPreview() {
        backfillPreviewState.value = null
    }

    fun applyBackfill() {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                val snapshot = state.value
                val count = wrongBatchRepository.applyBackfill(
                    sessions = snapshot.examSessions,
                    from = null, to = null, categoryIds = null,
                    attribution = snapshot.settings.dateAttribution,
                )
                backfillPreviewState.value = null
                eventChannel.send(
                    UiEvent.Message(
                        if (count > 0) "已导入 $count 个历史错题袋；历史错题不计入逾期提醒。"
                        else "没有需要导入的历史错题。",
                    ),
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("历史错题导入未完成，原数据未改动。"))
            } finally {
                busy.value = false
            }
        }
    }

    /** 计分记录没有可定义的「错题数」；用户显式补建袋（D16）。 */
    fun saveScoreSessionWithBatch(session: Session, wrongCount: Int) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                val id = sessionRepository.save(session, state.value.settings.dateAttribution)
                wrongBatchRepository.createManual(
                    originSessionId = id,
                    wrongCount = wrongCount,
                    note = null,
                    attribution = state.value.settings.dateAttribution,
                    session = session.copy(id = id),
                )
                eventChannel.send(UiEvent.RecordSaved)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IllegalArgumentException) {
                eventChannel.send(UiEvent.Message(exception.message ?: "保存未成功，请检查输入后重试。"))
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("保存未成功，请检查输入后重试；填写内容已保留。"))
            } finally {
                busy.value = false
            }
        }
    }

    /** 按当前预算生成今日处方；已有进行中的类别会被生成层跳过，重复点击也不会翻倍。 */
    fun generatePrescriptions() {
        if (busy.value) return
        val snapshot = state.value
        val stats = snapshot.stats ?: return
        if (snapshot.loading) return
        busy.value = true
        viewModelScope.launch {
            try {
                val activeCategoryIds = snapshot.prescriptions.filter { it.isActive }.map { it.categoryId }.toSet()
                val todayStart = snapshot.today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val activeToday = snapshot.prescriptions.count { it.isActive && it.createdAt >= todayStart }
                val slots = (PrescriptionPlanner.MAX_PER_DAY - activeToday).coerceAtLeast(0)
                if (slots == 0) {
                    eventChannel.send(UiEvent.Message("今天已经排满 ${PrescriptionPlanner.MAX_PER_DAY} 条，完成或跳过后再排。"))
                    return@launch
                }
                val plan = PrescriptionPlanner.plan(
                    stats = stats,
                    categories = snapshot.categories,
                    sessions = snapshot.sessions,
                    settings = snapshot.settings,
                    exam = snapshot.exam,
                    today = snapshot.today,
                    attribution = snapshot.settings.dateAttribution,
                    activeCategoryIds = activeCategoryIds,
                )
                val picked = plan.prescriptions.take(slots)
                if (picked.isEmpty()) {
                    eventChannel.send(UiEvent.Message(plan.note ?: "今天暂时没有需要新增的处方。"))
                    return@launch
                }
                prescriptionRepository.create(picked)
                val hint = plan.note?.let { "；$it" }.orEmpty()
                eventChannel.send(UiEvent.Message("已排好 ${picked.size} 条处方$hint"))
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("处方未能生成，请重试。"))
            } finally {
                busy.value = false
            }
        }
    }

    /** 开始处方：先落状态再开计时；已有计时时不动处方，避免同一条被两条计时消耗。 */
    fun startPrescription(prescription: Prescription, onStarted: () -> Unit) {
        if (busy.value) return
        if (activeTimer.value != null) {
            viewModelScope.launch {
                eventChannel.send(UiEvent.Message("已有计时在进行，结束或放弃后再开始这条处方。"))
            }
            return
        }
        busy.value = true
        viewModelScope.launch {
            try {
                val category = state.value.categories.find { it.id == prescription.categoryId }
                if (category == null) {
                    eventChannel.send(UiEvent.Message("这条处方的类别已不可用，可以跳过它。"))
                    return@launch
                }
                if (!prescriptionRepository.start(prescription.id)) {
                    eventChannel.send(UiEvent.Message("这条处方已经结束，不能再开始。"))
                    return@launch
                }
                val started = timerController.start(
                    category.subjectId,
                    category.id,
                    prescription.targetMinutes?.times(60),
                )
                if (started == null) {
                    eventChannel.send(UiEvent.Message("计时未能开始；处方已记为进行中，稍后可继续。"))
                } else {
                    eventChannel.send(UiEvent.Message("已按处方开始计时"))
                    onStarted()
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("处方未能开始，请重试。"))
            } finally {
                busy.value = false
            }
        }
    }

    /** 完成以用户判断为准，不按题量自动结算。验证照常要等完成后的独立记录。 */
    fun completePrescription(prescription: Prescription) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                if (prescriptionRepository.complete(prescription.id)) {
                    prescriptionRepository.evaluateAndRecord(prescription.id)
                    eventChannel.send(UiEvent.Message("处方已完成；验证需要前后各 3 次、各 32 题，完成后的记录先补后测。"))
                } else {
                    eventChannel.send(UiEvent.Message("这条处方已经结束。"))
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("处方状态未能保存，请重试。"))
            } finally {
                busy.value = false
            }
        }
    }

    fun skipPrescription(prescription: Prescription) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                if (prescriptionRepository.skip(prescription.id)) {
                    eventChannel.send(UiEvent.Message("已跳过这条处方"))
                } else {
                    eventChannel.send(UiEvent.Message("这条处方已经结束。"))
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("处方状态未能保存，请重试。"))
            } finally {
                busy.value = false
            }
        }
    }

    /**
     * 切换考试类型：科目、类别、记录、统计一起切换，计时中的记录不中断。
     */
    /** 记住本次使用的自定义倒计时时长；失败不影响本次计时。 */
    fun setCustomTimerMinutes(minutes: Int) {
        viewModelScope.launch {
            try {
                settingsRepository.setCustomTimerMinutes(minutes)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("自定义时长未能记住，下次再输入一次即可。"))
            }
        }
    }

    fun switchExam(type: ExamType) {
        if (state.value.currentExam == type) return
        viewModelScope.launch {
            try {
                settingsRepository.setCurrentExam(type)
                eventChannel.send(UiEvent.Message("已切换到${ExamTarget(type = type).displayName}"))
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("切换失敗，请重试。"))
            }
        }
    }

    /**
     * 新建用户自填科目。成功后把新 ID 回给界面，便于立刻选中。
     */
    fun createSubject(rawName: String, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            try {
                val id = categoryRepository.addSubject(rawName, state.first().currentExam)
                eventChannel.send(UiEvent.Message("已新建科目「${NameRules.normalize(rawName)}」"))
                onCreated(id)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IllegalArgumentException) {
                eventChannel.send(UiEvent.Message(exception.message ?: "名称不符合要求。"))
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("新建科目失败，请换一个名称重试。"))
            }
        }
    }

    /** 新建用户自填类别，挂到当前选中的科目下。 */
    fun createCategory(subjectId: Long, rawName: String, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            try {
                val id = categoryRepository.addCategory(subjectId, rawName)
                eventChannel.send(UiEvent.Message("已新建类别「${NameRules.normalize(rawName)}」"))
                onCreated(id)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IllegalArgumentException) {
                eventChannel.send(UiEvent.Message(exception.message ?: "名称不符合要求。"))
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("新建类别失败，请换一个名称重试。"))
            }
        }
    }

    // ---- v0.12 分类管理（H2-6）----

    /** 分类管理的动作形状一致：成功报结果，校验失败报原因，其它异常只提示不吞数据。 */
    private fun runCatalogAction(success: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                eventChannel.send(UiEvent.Message(success))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IllegalArgumentException) {
                eventChannel.send(UiEvent.Message(exception.message ?: "操作未完成。"))
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("操作未完成，请重试。"))
            }
        }
    }

    fun renameSubject(id: Long, rawName: String) =
        runCatalogAction("科目已改名") { categoryRepository.renameSubject(id, rawName) }

    fun renameCategory(id: Long, rawName: String) =
        runCatalogAction("类别已改名") { categoryRepository.renameCategory(id, rawName) }

    fun moveSubject(id: Long, delta: Int) {
        viewModelScope.launch {
            try {
                val moved = categoryRepository.moveSubject(id, delta)
                eventChannel.send(UiEvent.Message(if (moved) "科目顺序已更新" else "已经在边界，不能再移动。"))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IllegalArgumentException) {
                eventChannel.send(UiEvent.Message(exception.message ?: "操作未完成。"))
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("操作未完成，请重试。"))
            }
        }
    }

    fun moveCategory(id: Long, delta: Int) {
        viewModelScope.launch {
            try {
                val moved = categoryRepository.moveCategory(id, delta)
                eventChannel.send(UiEvent.Message(if (moved) "类别顺序已更新" else "已经在边界，不能再移动。"))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IllegalArgumentException) {
                eventChannel.send(UiEvent.Message(exception.message ?: "操作未完成。"))
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("操作未完成，请重试。"))
            }
        }
    }

    fun setSubjectArchived(id: Long, archived: Boolean) = runCatalogAction(
        if (archived) "科目已归档，历史记录与统计不变" else "科目已恢复",
    ) { categoryRepository.setSubjectArchived(id, archived) }

    fun setCategoryArchived(id: Long, archived: Boolean) = runCatalogAction(
        if (archived) "类别已归档，历史记录与统计不变" else "类别已恢复",
    ) { categoryRepository.setCategoryArchived(id, archived) }

    fun deleteSubject(id: Long) = runCatalogAction("科目已删除") { categoryRepository.deleteSubject(id) }

    fun deleteCategory(id: Long) = runCatalogAction("类别已删除") { categoryRepository.deleteCategory(id) }

    /**
     * 导出 CSV 到用户选择的文件。写入失败只提示，不动已保存的练习记录。
     */
    fun exportCsv(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val snapshot = state.first()
                if (snapshot.sessions.isEmpty()) {
                    eventChannel.send(UiEvent.Message("当前考试还没有可导出的记录。"))
                    return@launch
                }
                val model = ReportBuilder.build(snapshot.sessions, snapshot.subjects, snapshot.categories)
                val csv = CsvWriter.write(model)
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(csv.toByteArray(Charsets.UTF_8))
                    } ?: error("无法打开目标文件")
                }
                eventChannel.send(UiEvent.Message("已导出 ${model.rows.size} 条记录"))
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("导出未完成，请换一个保存位置重新尝试。"))
            }
        }
    }

    /** 导出全库 JSON；快照与当前考试无关，换机后可完整恢复。 */
    fun exportBackup(uri: Uri) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                val payload = backupRepository.snapshot(BuildConfig.VERSION_NAME)
                val json = BackupCodec.encode(payload)
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("无法打开目标文件")
                }
                settingsRepository.markBackupDone()
                eventChannel.send(UiEvent.Message("已备份 ${payload.sessions.size} 条记录"))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IllegalArgumentException) {
                eventChannel.send(UiEvent.Message(exception.message ?: "备份未完成，请换一个保存位置重试。"))
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("备份未完成，请换一个保存位置重试。"))
            } finally {
                busy.value = false
            }
        }
    }

    /** 记住用户选择的备份文件夹；权限持久化失败时不写入设置，避免下次静默失败。 */
    fun setBackupFolder(uri: Uri) {
        viewModelScope.launch {
            try {
                appContext.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                val name = folderBackup.displayName(uri)?.takeIf { it.isNotBlank() } ?: "已选择的文件夹"
                settingsRepository.setBackupFolder(uri.toString(), name)
                eventChannel.send(UiEvent.Message("已设为备份文件夹：$name"))
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("无法使用这个文件夹，请换一个位置重试。"))
            }
        }
    }

    /** 立即向已选择的文件夹写一份备份。 */
    fun backupToFolder() {
        if (busy.value) return
        val folder = state.value.backup.folderUri
        if (folder == null) {
            viewModelScope.launch { eventChannel.send(UiEvent.Message("请先选择备份文件夹。")) }
            return
        }
        busy.value = true
        viewModelScope.launch {
            try {
                writeFolderBackup(folder, silent = false)
            } finally {
                busy.value = false
            }
        }
    }

    private suspend fun writeFolderBackup(folderUri: String, silent: Boolean) {
        try {
            val payload = backupRepository.snapshot(BuildConfig.VERSION_NAME)
            val json = BackupCodec.encode(payload)
            val name = folderBackup.write(Uri.parse(folderUri), json)
            settingsRepository.markBackupDone()
            if (!silent) eventChannel.send(UiEvent.Message("已备份到文件夹：$name"))
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            if (!silent) eventChannel.send(UiEvent.Message("备份到文件夹未完成，请确认文件夹仍可访问。"))
        }
    }

    fun dismissBackupReminder() {
        viewModelScope.launch {
            runCatching { settingsRepository.dismissBackupReminder() }
        }
    }

    /** 只读取、校验并展示预览；真正的替换要等用户在对话框确认。 */
    fun previewRestore(uri: Uri) {        if (busy.value) return
        if (hasTimerState()) {
            viewModelScope.launch { eventChannel.send(UiEvent.Message("计时进行中，结束或放弃后再恢复备份。")) }
            return
        }
        busy.value = true
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { readBackupBytes(uri) }
                val payload = BackupCodec.decode(bytes)
                BackupValidator.requireValid(payload)
                pendingRestorePayload = payload
                restorePreviewState.value = RestorePreview(
                    summary = BackupCodec.summary(payload),
                    preferencesAvailable = payload.settings != null || payload.currentExam != null,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IllegalArgumentException) {
                eventChannel.send(UiEvent.Message(exception.message ?: "备份文件无法读取，原数据未改动。"))
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("备份文件无法读取，原数据未改动。"))
            } finally {
                busy.value = false
            }
        }
    }

    fun cancelRestore() {
        if (busy.value) return
        pendingRestorePayload = null
        restorePreviewState.value = null
    }

    fun confirmRestore(restorePreferences: Boolean) {
        val payload = pendingRestorePayload ?: return
        if (busy.value) return
        if (hasTimerState()) {
            pendingRestorePayload = null
            restorePreviewState.value = null
            viewModelScope.launch { eventChannel.send(UiEvent.Message("计时进行中，结束或放弃后再恢复备份。")) }
            return
        }
        busy.value = true
        // 恢复会整表替换，挂起的删除任务不能在替换后按旧 ID 再删一次。
        deleteJob?.cancel()
        pendingDelete.value = null
        viewModelScope.launch {
            try {
                val report = backupRepository.restore(payload, restorePreferences, BuildConfig.VERSION_NAME)
                pendingRestorePayload = null
                restorePreviewState.value = null
                val preferences = when {
                    !restorePreferences -> ""
                    report.preferencesRestored -> "，偏好已恢复"
                    else -> "，偏好未恢复"
                }
                eventChannel.send(
                    UiEvent.Message("已恢复 ${report.summary.sessions} 条记录$preferences；恢复前已自动备份"),
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IllegalArgumentException) {
                eventChannel.send(UiEvent.Message(exception.message ?: "恢复未完成，原数据保持不变。"))
            } catch (exception: IllegalStateException) {
                eventChannel.send(UiEvent.Message(exception.message ?: "恢复未完成，原数据保持不变。"))
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("恢复未完成，原数据保持不变。"))
            } finally {
                busy.value = false
            }
        }
    }

    /** 读取并解析 CSV，只做预览；真正写入要等用户确认，且先自动备份。 */
    fun previewCsvImport(uri: Uri) {
        if (busy.value) return
        busy.value = true
        importTable = null
        importPreviewState.value = null
        val fileName = importDisplayName(uri)
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { readImportBytes(uri) }
                val (table, scan) = withContext(Dispatchers.Default) {
                    val table = CsvImport.parse(CsvImport.decode(bytes))
                    table to CsvImport.scan(table, CsvImport.detect(table.header), LocalDate.now())
                }
                importTable = table
                importPreviewState.value = buildImportPreview(fileName, scan)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IllegalArgumentException) {
                eventChannel.send(UiEvent.Message(exception.message ?: "文件无法读取，未导入任何记录。"))
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("文件无法读取，未导入任何记录。"))
            } finally {
                busy.value = false
            }
        }
    }

    fun remapImportField(field: ImportField, index: Int?) {
        val table = importTable ?: return
        val current = importPreviewState.value?.scan?.mapping ?: CsvImport.detect(table.header)
        val columns = current.columns.toMutableMap()
        if (index == null) {
            columns.remove(field)
        } else {
            columns.entries.removeAll { it.value == index && it.key != field }
            columns[field] = index
        }
        viewModelScope.launch {
            val fileName = importPreviewState.value?.fileName ?: return@launch
            val scan = withContext(Dispatchers.Default) {
                CsvImport.scan(table, ImportMapping(columns), LocalDate.now())
            }
            importPreviewState.value = buildImportPreview(fileName, scan)
        }
    }

    fun resolveImportPair(key: String, resolution: ImportResolution) {
        val preview = importPreviewState.value ?: return
        val updated = preview.copy(resolutions = preview.resolutions + (key to resolution))
        importPreviewState.value = updated.copy(duplicateLines = duplicateLines(updated))
    }

    fun setImportSkipDuplicates(skip: Boolean) {
        val preview = importPreviewState.value ?: return
        importPreviewState.value = preview.copy(skipDuplicates = skip)
    }

    fun cancelCsvImport() {
        if (busy.value) return
        importTable = null
        importPreviewState.value = null
    }

    fun confirmCsvImport() {
        val preview = importPreviewState.value ?: return
        if (busy.value) return
        if (preview.scan.missingRequired.isNotEmpty()) {
            viewModelScope.launch { eventChannel.send(UiEvent.Message("还有列没有对应上，暂不能导入。")) }
            return
        }
        if (preview.scan.errors.isNotEmpty()) {
            viewModelScope.launch { eventChannel.send(UiEvent.Message("有 ${preview.scan.errors.size} 行无法解析，整批不会写入。")) }
            return
        }
        if (preview.importableCount == 0) {
            viewModelScope.launch { eventChannel.send(UiEvent.Message("没有可导入的记录。")) }
            return
        }
        busy.value = true
        viewModelScope.launch {
            try {
                val report = importRepository.apply(
                    ImportPlan(
                        candidates = preview.scan.candidates,
                        resolutions = preview.resolutions,
                        knownCategoryIds = preview.knownPairCategoryIds,
                        duplicateLines = preview.duplicateLines,
                        skipDuplicates = preview.skipDuplicates,
                        currentExam = state.value.currentExam,
                    ),
                    BuildConfig.VERSION_NAME,
                )
                importTable = null
                importPreviewState.value = null
                val created = if (report.createdCategories > 0) "，新建 ${report.createdCategories} 个类别" else ""
                val unlinked = if (report.unlinkedRedoRows > 0) {
                    "；${report.unlinkedRedoRows} 条复做记录未挂接错题袋（CSV 不表达袋关联），不计入消化率"
                } else ""
                eventChannel.send(
                    UiEvent.Imported(
                        "已导入 ${report.inserted} 条记录$created；跳过疑似重复 ${report.skippedDuplicates} 条、未匹配 ${report.skippedUnknown} 条$unlinked",
                    ),
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IllegalArgumentException) {
                eventChannel.send(UiEvent.Message(exception.message ?: "导入未完成，原有记录未改动。"))
            } catch (_: Exception) {
                eventChannel.send(UiEvent.Message("导入未完成，原有记录未改动。"))
            } finally {
                busy.value = false
            }
        }
    }

    private fun buildImportPreview(fileName: String, scan: ImportScan): CsvImportPreview {
        val snapshot = state.value
        val subjectNames = snapshot.subjects.associate { it.id to NameRules.normalize(it.name) }
        val categoriesByPair = mutableMapOf<String, Long>()
        snapshot.categories.forEach { category ->
            val subjectName = subjectNames[category.subjectId] ?: return@forEach
            categoriesByPair[ImportPair(subjectName, NameRules.normalize(category.name)).key] = category.id
        }
        val known = mutableMapOf<String, Long>()
        val knownRows = linkedMapOf<String, ImportPairSummary>()
        val unknown = linkedMapOf<String, ImportPairSummary>()
        scan.candidates.forEach { candidate ->
            val pair = ImportPair(candidate.subjectName, candidate.categoryName)
            val categoryId = categoriesByPair[pair.key]
            if (categoryId != null) {
                known[pair.key] = categoryId
                val previous = knownRows[pair.key]
                knownRows[pair.key] = ImportPairSummary(
                    key = pair.key,
                    subjectName = candidate.subjectName,
                    categoryName = candidate.categoryName,
                    rowCount = (previous?.rowCount ?: 0) + 1,
                    categoryId = categoryId,
                )
            } else {
                val previous = unknown[pair.key]
                unknown[pair.key] = ImportPairSummary(
                    key = pair.key,
                    subjectName = candidate.subjectName,
                    categoryName = candidate.categoryName,
                    rowCount = (previous?.rowCount ?: 0) + 1,
                )
            }
        }
        val previousPreview = importPreviewState.value
        val resolutions = unknown.keys.associateWith { key ->
            previousPreview?.resolutions?.get(key) ?: ImportResolution.Skip
        }
        val preview = CsvImportPreview(
            fileName = fileName,
            scan = scan,
            unknownPairs = unknown.values.toList(),
            knownPairs = knownRows.values.toList(),
            knownPairCategoryIds = known,
            resolutions = resolutions,
            skipDuplicates = previousPreview?.skipDuplicates ?: true,
        )
        return preview.copy(duplicateLines = duplicateLines(preview))
    }

    private fun duplicateLines(preview: CsvImportPreview): Set<Int> {
        val existing = state.value.examSessions
            .map { CsvImport.fingerprint(it.categoryId, it.startAt, it.totalCount) }
            .toSet()
        val mapped = preview.knownPairCategoryIds.toMutableMap()
        preview.resolutions.forEach { (key, resolution) ->
            if (resolution is ImportResolution.Existing) mapped[key] = resolution.categoryId
        }
        val seen = mutableSetOf<String>()
        val duplicates = linkedSetOf<Int>()
        preview.scan.candidates.sortedBy { it.line }.forEach { candidate ->
            val pairKey = ImportPair(candidate.subjectName, candidate.categoryName).key
            val categoryId = mapped[pairKey]
            // 待新建的类别还没有 ID，用“科目+类别”代替，仍能识别同一文件内的重复。
            val fingerprint = if (categoryId != null) {
                CsvImport.fingerprint(categoryId, candidate.startAt, candidate.total)
            } else {
                "pair:$pairKey|${candidate.startAt}|${candidate.total}"
            }
            if (!seen.add(fingerprint) || (categoryId != null && fingerprint in existing)) {
                duplicates.add(candidate.line)
            }
        }
        return duplicates
    }

    /** SAF 的 URI 末段通常是编码后的长串，文件名要从文档提供方取。 */
    private fun importDisplayName(uri: Uri): String {
        val providerName = runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        return providerName?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "导入文件.csv"
    }

    private fun readImportBytes(uri: Uri): ByteArray {
        val stream = appContext.contentResolver.openInputStream(uri) ?: error("无法打开所选文件")
        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= CsvImport.MAX_BYTES) { "文件超过 20MB，无法导入。" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    private fun hasTimerState(): Boolean =
        timerController.timers.value.let { it.active != null || it.finished != null }

    private fun readBackupBytes(uri: Uri): ByteArray {
        val stream = appContext.contentResolver.openInputStream(uri) ?: error("无法打开备份文件")
        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= BackupCodec.MAX_BYTES) { "备份文件超出大小限制，无法读取。" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }
}

/** 兼容旧调用点：实现已收敛到 domain.stats，UI 侧不再单独算一次。 */
fun sessionDate(session: Session, attribution: DateAttribution): LocalDate =
    com.tiji.studyclock.domain.stats.sessionDate(session, attribution)
