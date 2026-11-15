package com.tiji.studyclock.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM subject ORDER BY sortOrder, id")
    fun observeSubjects(): Flow<List<SubjectEntity>>

    @Query("SELECT * FROM category ORDER BY subjectId, sortOrder, id")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE id = :id")
    suspend fun getCategory(id: Long): CategoryEntity?

    @Query("SELECT COUNT(*) FROM subject WHERE examType = :examType AND name = :name")
    suspend fun countSubjectName(examType: String, name: String): Int

    @Query("SELECT COUNT(*) FROM category WHERE subjectId = :subjectId AND name = :name")
    suspend fun countCategoryName(subjectId: Long, name: String): Int

    @Query("SELECT * FROM subject WHERE examType = :examType AND name = :name LIMIT 1")
    suspend fun findSubject(examType: String, name: String): SubjectEntity?

    @Query("SELECT * FROM category WHERE subjectId = :subjectId AND name = :name LIMIT 1")
    suspend fun findCategory(subjectId: Long, name: String): CategoryEntity?

    @Query("SELECT subjectId FROM category WHERE id = :id")
    suspend fun subjectIdOf(id: Long): Long?

    @Query("SELECT examType FROM subject WHERE id = (SELECT subjectId FROM category WHERE id = :categoryId)")
    suspend fun examTypeOfCategory(categoryId: Long): String?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM subject WHERE examType = :examType")
    suspend fun maxSubjectSortOrder(examType: String): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM category WHERE subjectId = :subjectId")
    suspend fun maxCategorySortOrder(subjectId: Long): Int

    // ---- v0.12 分类管理（H2-6）：改名、排序、引用计数 ----

    @Query("SELECT * FROM subject WHERE id = :id")
    suspend fun getSubject(id: Long): SubjectEntity?

    @Query("SELECT * FROM subject WHERE examType = :examType ORDER BY sortOrder, id")
    suspend fun subjectsInExam(examType: String): List<SubjectEntity>

    @Query("SELECT * FROM category WHERE subjectId = :subjectId ORDER BY sortOrder, id")
    suspend fun categoriesInSubject(subjectId: Long): List<CategoryEntity>

    @Query("SELECT COUNT(*) FROM subject WHERE examType = :examType AND name = :name AND id != :excludeId")
    suspend fun countSubjectNameExcept(examType: String, name: String, excludeId: Long): Int

    @Query("SELECT COUNT(*) FROM category WHERE subjectId = :subjectId AND name = :name AND id != :excludeId")
    suspend fun countCategoryNameExcept(subjectId: Long, name: String, excludeId: Long): Int

    @Query("UPDATE subject SET name = :name WHERE id = :id")
    suspend fun renameSubject(id: Long, name: String): Int

    @Query("UPDATE category SET name = :name WHERE id = :id")
    suspend fun renameCategory(id: Long, name: String): Int

    @Query("UPDATE subject SET sortOrder = :order WHERE id = :id")
    suspend fun setSubjectSortOrder(id: Long, order: Int): Int

    @Query("UPDATE category SET sortOrder = :order WHERE id = :id")
    suspend fun setCategorySortOrder(id: Long, order: Int): Int

    @Query("SELECT COUNT(*) FROM session WHERE subjectId = :subjectId")
    suspend fun countSessionsOfSubject(subjectId: Long): Int

    @Query("SELECT COUNT(*) FROM session WHERE categoryId = :categoryId")
    suspend fun countSessionsOfCategory(categoryId: Long): Int

    @Query("SELECT COUNT(*) FROM wrong_batch WHERE subjectId = :subjectId")
    suspend fun countBatchesOfSubject(subjectId: Long): Int

    @Query("SELECT COUNT(*) FROM wrong_batch WHERE categoryId = :categoryId")
    suspend fun countBatchesOfCategory(categoryId: Long): Int

    @Query("SELECT COUNT(*) FROM prescription WHERE categoryId = :categoryId")
    suspend fun countPrescriptionsOfCategory(categoryId: Long): Int

    @Query(
        "SELECT COUNT(*) FROM prescription WHERE categoryId IN " +
            "(SELECT id FROM category WHERE subjectId = :subjectId)",
    )
    suspend fun countPrescriptionsOfSubject(subjectId: Long): Int

    @Query("SELECT COUNT(*) FROM category WHERE subjectId = :subjectId")
    suspend fun countCategoriesOfSubject(subjectId: Long): Int

    @Query("DELETE FROM category WHERE subjectId = :subjectId")
    suspend fun deleteCategoriesOfSubject(subjectId: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSubject(subject: SubjectEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCategory(category: CategoryEntity): Long

    @Query("UPDATE subject SET archived = :archived WHERE id = :id")
    suspend fun archiveSubject(id: Long, archived: Boolean): Int

    @Query("UPDATE category SET archived = :archived WHERE id = :id")
    suspend fun archiveCategory(id: Long, archived: Boolean): Int

    /** v5：用户可把任意类别改成计分模式，或改回客观题。 */
    @Query("UPDATE category SET defaultMode = :mode WHERE id = :id")
    suspend fun setDefaultMode(id: Long, mode: String): Int

    /** v5：首次录入计分后按类别记忆满分；传 null 表示清除记忆。 */
    @Query("UPDATE category SET defaultMaxScoreTenths = :maxScoreTenths WHERE id = :id")
    suspend fun setDefaultMaxScore(id: Long, maxScoreTenths: Int?): Int

    // RESTRICT requires callers to explicitly remove empty categories before their subject.
    @Query("DELETE FROM subject WHERE id = :id")
    suspend fun deleteSubject(id: Long): Int

    @Query("DELETE FROM category WHERE id = :id")
    suspend fun deleteCategory(id: Long): Int
}
