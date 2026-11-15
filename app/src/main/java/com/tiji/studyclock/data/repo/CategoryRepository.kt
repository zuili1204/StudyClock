package com.tiji.studyclock.data.repo

import androidx.room.withTransaction
import com.tiji.studyclock.data.db.AppDatabase
import com.tiji.studyclock.data.db.CategoryEntity
import com.tiji.studyclock.data.db.SubjectEntity
import com.tiji.studyclock.data.db.toDomain
import com.tiji.studyclock.data.seed.CategorySeed
import com.tiji.studyclock.domain.model.CatalogRules
import com.tiji.studyclock.domain.model.Category
import com.tiji.studyclock.domain.model.ExamType
import com.tiji.studyclock.domain.model.NameRules
import com.tiji.studyclock.domain.model.RecordMode
import com.tiji.studyclock.domain.model.ScoreRules
import com.tiji.studyclock.domain.model.Subject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepository @Inject constructor(private val database: AppDatabase) {
    private val dao = database.categoryDao()

    // Archived entries remain available for resolving historical records.
    val subjects: Flow<List<Subject>> = dao.observeSubjects()
        .map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()
    val categories: Flow<List<Category>> = dao.observeCategories()
        .map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()

    /**
     * 用户自建科目。名称先做纯规则校验与去空白，重名由唯一索引兜底。
     * 返回新科目 ID，供调用方立即选中。
     */
    suspend fun addSubject(rawName: String, examType: ExamType): Long {
        NameRules.error(rawName)?.let { throw IllegalArgumentException(it) }
        val name = NameRules.normalize(rawName)
        require(dao.countSubjectName(examType.key, name) == 0) { duplicateSubjectMessage(examType, name) }
        val order = dao.maxSubjectSortOrder(examType.key) + 1
        return dao.insertSubject(
            SubjectEntity(name = name, colorHex = CUSTOM_COLOR, sortOrder = order, examType = examType.key),
        )
    }

    /** 用户自建类别，挂到指定科目下；同名只在该科目内判重。 */
    suspend fun addCategory(subjectId: Long, rawName: String): Long {
        require(subjectId > 0) { "请先选择科目" }
        NameRules.error(rawName)?.let { throw IllegalArgumentException(it) }
        val name = NameRules.normalize(rawName)
        require(dao.countCategoryName(subjectId, name) == 0) { duplicateCategoryMessage(subjectId, name) }
        val order = dao.maxCategorySortOrder(subjectId) + 1
        return dao.insertCategory(CategoryEntity(subjectId = subjectId, name = name, sortOrder = order))
    }

    /** 导入时按名称复用科目；不存在才新建，避免静默产生重复。 */
    suspend fun ensureSubject(rawName: String, examType: ExamType): Long {
        val name = NameRules.normalize(rawName)
        dao.findSubject(examType.key, name)?.let { return it.id }
        return addSubject(name, examType)
    }

    /** 导入时按名称复用类别；同名只在该科目内复用。 */
    suspend fun ensureCategory(subjectId: Long, rawName: String): Long {
        require(subjectId > 0) { "请先选择科目" }
        val name = NameRules.normalize(rawName)
        dao.findCategory(subjectId, name)?.let { return it.id }
        return addCategory(subjectId, name)
    }

    /** v5：更改类别的默认记录模式，只影响结算页预填，不改写任何历史记录。 */
    suspend fun setDefaultMode(categoryId: Long, mode: RecordMode) {
        // 专注是「这一段没做题」的一次性事实，不是某个类别的常态口径（D27）。
        require(mode != RecordMode.FOCUS) { "专注不能设为类别的默认记录方式" }
        dao.setDefaultMode(categoryId, mode.name)
    }

    /** v5：按类别记忆常用满分；不预置、不猜测，null 表示清除。 */
    suspend fun rememberMaxScore(categoryId: Long, maxScoreTenths: Int?) {
        if (maxScoreTenths != null) {
            require(maxScoreTenths in 1..ScoreRules.MAX_SCORE_TENTHS) { "满分超出允许范围" }
        }
        dao.setDefaultMaxScore(categoryId, maxScoreTenths)
    }

    // ---- v0.12 分类管理（H2-6）：改名、排序、归档、删除 ----

    /** 改名只动 `name`；统计、处方、CSV 与备份都按 ID 关联，不需要连带写。 */
    suspend fun renameSubject(id: Long, rawName: String) {
        val entity = dao.getSubject(id) ?: throw IllegalArgumentException("科目不存在，可能已在其它设备删除。")
        val siblings = dao.subjectsInExam(entity.examType).map { it.id to it.name }
        CatalogRules.renameError(rawName, siblings, id, id in CategorySeed.presetSubjectIds)
            ?.let { throw IllegalArgumentException(it) }
        dao.renameSubject(id, NameRules.normalize(rawName))
    }

    suspend fun renameCategory(id: Long, rawName: String) {
        val entity = dao.getCategory(id) ?: throw IllegalArgumentException("类别不存在，可能已在其它设备删除。")
        val siblings = dao.categoriesInSubject(entity.subjectId).map { it.id to it.name }
        CatalogRules.renameError(rawName, siblings, id, id in CategorySeed.presetCategoryIds)
            ?.let { throw IllegalArgumentException(it) }
        dao.renameCategory(id, NameRules.normalize(rawName))
    }

    /**
     * 上移/下移一项，并在同一事务里把作用域内的启用项重写为 0..n-1（D36）。
     * 返回 false 表示已在边界（界面据此禁用并给出提示）。
     */
    suspend fun moveSubject(id: Long, delta: Int): Boolean {
        val entity = dao.getSubject(id) ?: throw IllegalArgumentException("科目不存在，可能已在其它设备删除。")
        val ids = dao.subjectsInExam(entity.examType).filterNot { it.archived }.map { it.id }
        val next = CatalogRules.moved(ids, id, delta) ?: return false
        database.withTransaction {
            next.forEachIndexed { index, subjectId -> dao.setSubjectSortOrder(subjectId, index) }
        }
        return true
    }

    suspend fun moveCategory(id: Long, delta: Int): Boolean {
        val entity = dao.getCategory(id) ?: throw IllegalArgumentException("类别不存在，可能已在其它设备删除。")
        val ids = dao.categoriesInSubject(entity.subjectId).filterNot { it.archived }.map { it.id }
        val next = CatalogRules.moved(ids, id, delta) ?: return false
        database.withTransaction {
            next.forEachIndexed { index, categoryId -> dao.setCategorySortOrder(categoryId, index) }
        }
        return true
    }

    /** 归档只改标记；恢复时追加到作用域末尾，避免和历史顺序打架。 */
    suspend fun setSubjectArchived(id: Long, archived: Boolean) {
        val entity = dao.getSubject(id) ?: throw IllegalArgumentException("科目不存在，可能已在其它设备删除。")
        if (entity.archived == archived) return
        if (archived) {
            val others = dao.subjectsInExam(entity.examType).count { !it.archived && it.id != id }
            CatalogRules.subjectArchiveError(others)?.let { throw IllegalArgumentException(it) }
        }
        database.withTransaction {
            if (!archived) dao.setSubjectSortOrder(id, dao.maxSubjectSortOrder(entity.examType) + 1)
            dao.archiveSubject(id, archived)
            resequenceSubjects(entity.examType)
        }
    }

    suspend fun setCategoryArchived(id: Long, archived: Boolean) {
        val entity = dao.getCategory(id) ?: throw IllegalArgumentException("类别不存在，可能已在其它设备删除。")
        if (entity.archived == archived) return
        if (archived) {
            val subjectArchived = dao.getSubject(entity.subjectId)?.archived != false
            val others = dao.categoriesInSubject(entity.subjectId).count { !it.archived && it.id != id }
            CatalogRules.categoryArchiveError(others, subjectArchived)?.let { throw IllegalArgumentException(it) }
        }
        database.withTransaction {
            if (!archived) dao.setCategorySortOrder(id, dao.maxCategorySortOrder(entity.subjectId) + 1)
            dao.archiveCategory(id, archived)
            resequenceCategories(entity.subjectId)
        }
    }

    /** 物理删除；预置与任何被引用的项都会被 [CatalogRules.deleteError] 拦下（D37）。 */
    suspend fun deleteCategory(id: Long) {
        val entity = dao.getCategory(id) ?: return
        val references = CatalogRules.References(
            records = dao.countSessionsOfCategory(id),
            batches = dao.countBatchesOfCategory(id),
            prescriptions = dao.countPrescriptionsOfCategory(id),
        )
        CatalogRules.deleteError(id in CategorySeed.presetCategoryIds, references)
            ?.let { throw IllegalArgumentException(it) }
        database.withTransaction {
            dao.deleteCategory(id)
            resequenceCategories(entity.subjectId)
        }
    }

    /** 科目删除会带走其下所有类别；任一引用（含类别上的处方）都会整体拒绝。 */
    suspend fun deleteSubject(id: Long) {
        val entity = dao.getSubject(id) ?: return
        val references = CatalogRules.References(
            records = dao.countSessionsOfSubject(id),
            batches = dao.countBatchesOfSubject(id),
            prescriptions = dao.countPrescriptionsOfSubject(id),
        )
        CatalogRules.deleteError(id in CategorySeed.presetSubjectIds, references)
            ?.let { throw IllegalArgumentException(it) }
        database.withTransaction {
            dao.deleteCategoriesOfSubject(id)
            dao.deleteSubject(id)
            resequenceSubjects(entity.examType)
        }
    }

    private suspend fun resequenceSubjects(examType: String) {
        dao.subjectsInExam(examType).filterNot { it.archived }
            .forEachIndexed { index, row -> dao.setSubjectSortOrder(row.id, index) }
    }

    private suspend fun resequenceCategories(subjectId: Long) {
        dao.categoriesInSubject(subjectId).filterNot { it.archived }
            .forEachIndexed { index, row -> dao.setCategorySortOrder(row.id, index) }
    }

    private suspend fun duplicateSubjectMessage(examType: ExamType, name: String): String =
        if (dao.findSubject(examType.key, name)?.archived == true) {
            "同考试下已有同名科目，它在「已归档」里，可以恢复后复用。"
        } else {
            "该考试下已有同名科目"
        }

    private suspend fun duplicateCategoryMessage(subjectId: Long, name: String): String =
        if (dao.findCategory(subjectId, name)?.archived == true) {
            "同科目下已有同名类别，它在「已归档」里，可以恢复后复用。"
        } else {
            "该科目下已有同名类别"
        }

    private companion object {
        // 自定义科目用一个中性色，避免与 6 个预置科目的配色冲突。
        const val CUSTOM_COLOR = "#7C8B99"
    }
}
