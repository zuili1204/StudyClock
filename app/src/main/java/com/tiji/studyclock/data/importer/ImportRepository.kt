package com.tiji.studyclock.data.importer

import androidx.room.withTransaction
import com.tiji.studyclock.data.backup.BackupRepository
import com.tiji.studyclock.data.db.AppDatabase
import com.tiji.studyclock.data.db.toEntity
import com.tiji.studyclock.data.repo.CategoryRepository
import com.tiji.studyclock.domain.model.ExamType
import com.tiji.studyclock.domain.model.Session
import com.tiji.studyclock.domain.model.SessionKind
import com.tiji.studyclock.domain.model.SessionSource
import javax.inject.Inject
import javax.inject.Singleton

/** 用户在预览里对无法自动匹配的“科目/类别”做出的选择。 */
sealed interface ImportResolution {
    data object Skip : ImportResolution
    data class Existing(val categoryId: Long) : ImportResolution
    data object Create : ImportResolution
}

data class ImportPair(val subjectName: String, val categoryName: String) {
    val key: String get() = "$subjectName\u0000$categoryName"
}

data class ImportPlan(
    val candidates: List<ImportCandidate>,
    /** 未知科目/类别的选择；自动匹配到的熟面孔走 [knownCategoryIds]。 */
    val resolutions: Map<String, ImportResolution>,
    val knownCategoryIds: Map<String, Long>,
    val duplicateLines: Set<Int>,
    val skipDuplicates: Boolean,
    val currentExam: ExamType,
)

data class ImportReport(
    val inserted: Int,
    val skippedDuplicates: Int,
    val skippedUnknown: Int,
    val createdCategories: Int,
    val autoBackupName: String,
    /** v5：CSV 不表达错题袋关联，导入的复做行需要在应用内重新挂接。 */
    val unlinkedRedoRows: Int = 0,
)

/**
 * 导入落库：先自动备份，再在单个 Room 事务里创建缺失类别并批量写入记录。
 * 任何一行失败都会回滚整批，不会留下半套数据；解析与字段校验在 [CsvImport] 完成。
 */
@Singleton
class ImportRepository @Inject constructor(
    private val database: AppDatabase,
    private val categoryRepository: CategoryRepository,
    private val backupRepository: BackupRepository,
) {
    private val categoryDao = database.categoryDao()
    private val sessionDao = database.sessionDao()

    suspend fun apply(plan: ImportPlan, appVersion: String): ImportReport {
        require(plan.candidates.isNotEmpty()) { "没有可导入的记录。" }
        val autoBackup = backupRepository.autoBackupForImport(appVersion)
        var inserted = 0
        var skippedDuplicates = 0
        var skippedUnknown = 0
        var createdCategories = 0
        var unlinkedRedoRows = 0
        val now = System.currentTimeMillis()

        database.withTransaction {
            val resolved = mutableMapOf<String, Long?>()
            val seen = mutableSetOf<String>()
            val categoryInfo = mutableMapOf<Long, Pair<Long, String>>()
            val rows = mutableListOf<com.tiji.studyclock.data.db.SessionEntity>()
            plan.candidates.sortedBy { it.line }.forEach { candidate ->
                val pair = ImportPair(candidate.subjectName, candidate.categoryName)
                val categoryId = if (pair.key in resolved) resolved[pair.key] else {
                    val id = resolve(pair, plan) { createdCategories++ }
                    resolved[pair.key] = id
                    id
                }
                if (categoryId == null) {
                    skippedUnknown++
                    return@forEach
                }
                if (plan.skipDuplicates) {
                    // 文件内同指纹的行也算重复：同一批里重复导入不该写两遍。
                    val fingerprint = CsvImport.fingerprint(categoryId, candidate.startAt, candidate.total)
                    if (candidate.line in plan.duplicateLines || !seen.add(fingerprint)) {
                        skippedDuplicates++
                        return@forEach
                    }
                }
                val (subjectId, examTypeKey) = categoryInfo.getOrPut(categoryId) {
                    val subject = categoryDao.subjectIdOf(categoryId) ?: error("类别不存在，导入已中止。")
                    subject to (categoryDao.examTypeOfCategory(categoryId) ?: error("科目不存在，导入已中止。"))
                }
                rows.add(
                    Session(
                        categoryId = categoryId,
                        subjectId = subjectId,
                        startAt = candidate.startAt,
                        endAt = candidate.endAt,
                        durationSec = candidate.durationSec,
                        totalCount = candidate.total,
                        correctCount = candidate.correct,
                        answerSequence = candidate.sequence,
                        wrongTags = candidate.wrongTags,
                        difficulty = candidate.difficulty,
                        energy = candidate.energy,
                        source = SessionSource.IMPORT,
                        examType = ExamType.fromKey(examTypeKey),
                        createdAt = now,
                        updatedAt = now,
                        mode = candidate.mode,
                        kind = candidate.kind,
                        scoreTenths = candidate.scoreTenths,
                        maxScoreTenths = candidate.maxScoreTenths,
                        scoreLabel = candidate.scoreLabel,
                        // CSV 不保存袋 ID：复做行的关联置空，由用户在应用内重新挂接。
                        batchId = null,
                    ).toEntity(),
                )
                inserted++
                if (candidate.kind == SessionKind.REDO) unlinkedRedoRows++
            }
            // Room 在这里按实体逐条执行，包在同一个事务里，失败即整批回滚。
            if (rows.isNotEmpty()) sessionDao.insertImported(rows)
        }

        return ImportReport(
            inserted = inserted,
            skippedDuplicates = skippedDuplicates,
            skippedUnknown = skippedUnknown,
            createdCategories = createdCategories,
            autoBackupName = autoBackup,
            unlinkedRedoRows = unlinkedRedoRows,
        )
    }

    private suspend fun resolve(pair: ImportPair, plan: ImportPlan, onCreated: () -> Unit): Long? {
        plan.knownCategoryIds[pair.key]?.let { known ->
            if (categoryDao.subjectIdOf(known) != null) return known
        }
        return when (val choice = plan.resolutions[pair.key] ?: ImportResolution.Skip) {
            ImportResolution.Skip -> null
            is ImportResolution.Existing ->
                if (categoryDao.subjectIdOf(choice.categoryId) != null) choice.categoryId else null
            ImportResolution.Create -> {
                val subjectId = categoryRepository.ensureSubject(pair.subjectName, plan.currentExam)
                val existing = categoryDao.findCategory(subjectId, pair.categoryName)
                existing?.id ?: categoryRepository.ensureCategory(subjectId, pair.categoryName).also { onCreated() }
            }
        }
    }
}
