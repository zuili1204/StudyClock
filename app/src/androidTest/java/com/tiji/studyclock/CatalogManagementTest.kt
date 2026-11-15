package com.tiji.studyclock

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tiji.studyclock.data.db.AppDatabase
import com.tiji.studyclock.data.db.CategoryEntity
import com.tiji.studyclock.data.db.SessionEntity
import com.tiji.studyclock.data.db.toDomain
import com.tiji.studyclock.data.repo.CategoryRepository
import com.tiji.studyclock.domain.model.CatalogRules
import com.tiji.studyclock.domain.model.ExamType
import com.tiji.studyclock.domain.model.SessionSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v0.12 分类管理（H2-6）在真实 SQLite 上的行为：
 * 改名判重、预置锁、上移下移、归档守卫、删除引用守卫。
 */
@RunWith(AndroidJUnit4::class)
class CatalogManagementTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: CategoryRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.SEED_CALLBACK).build()
        repository = CategoryRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun presetFlagsComeFromSeedIds() = runTest {
        val presets = repository.categories.first().filter { it.id <= 43 }
        assertTrue(presets.isNotEmpty())
        assertTrue(presets.all { it.isPreset })
        assertTrue(repository.subjects.first().all { it.isPreset })

        val customId = repository.addSubject("我的科目", ExamType.CIVIL_SERVANT)
        assertFalse(repository.subjects.first().single { it.id == customId }.isPreset)
        val categoryId = repository.addCategory(customId, "我的类别")
        assertFalse(repository.categories.first().single { it.id == categoryId }.isPreset)
    }

    @Test
    fun renameLocksPresetsAndRejectsDuplicates() = runTest {
        val presetError = failureOf { repository.renameSubject(1, "行测") }
        assertEquals(CatalogRules.PRESET_LOCKED, presetError)
        assertEquals(
            CatalogRules.PRESET_LOCKED,
            failureOf { repository.renameCategory(43, "大作文") },
        )

        val customId = repository.addSubject("我的科目", ExamType.CIVIL_SERVANT)
        assertTrue(failureOf { repository.renameSubject(customId, "常识判断") }.contains("同名"))
        repository.renameSubject(customId, "  行测  ")
        assertEquals("行测", repository.subjects.first().single { it.id == customId }.name)

        val categoryId = repository.addCategory(1, "自定义类别")
        assertTrue(failureOf { repository.renameCategory(categoryId, "政治常识") }.contains("同名"))
        repository.renameCategory(categoryId, "自定义类别二")
        assertEquals("自定义类别二", repository.categories.first().single { it.id == categoryId }.name)
    }

    @Test
    fun moveReordersWithinScopeAndStopsAtEdges() = runTest {
        assertEquals(listOf(1L, 2L, 3L), civilServantSubjectIds().take(3))
        assertFalse(repository.moveSubject(1, -1))
        assertTrue(repository.moveSubject(1, 1))
        assertEquals(listOf(2L, 1L, 3L, 4L, 5L, 6L), civilServantSubjectIds())
        assertFalse(repository.moveSubject(2, -1))

        // 类别只在所属科目内重排，其他科目不受影响。
        assertFalse(repository.moveCategory(1, -1))
        assertTrue(repository.moveCategory(1, 1))
        val subjectOne = repository.categories.first().filter { it.subjectId == 1L }.map { it.id }
        assertEquals(listOf(2L, 1L), subjectOne.take(2))
        assertFalse(repository.moveCategory(2, -1))
    }

    @Test
    fun archiveKeepsTheLastSubjectAndCategoryAvailable() = runTest {
        for (id in 2L..6L) repository.setSubjectArchived(id, true)
        assertTrue(
            failureOf { repository.setSubjectArchived(1, true) }.contains("至少保留一个启用科目"),
        )
        // 恢复追加到末尾，而不是插回原来的位置。
        repository.setSubjectArchived(2, false)
        assertEquals(2L, civilServantSubjectIds().last())

        val subjectOneCategories = repository.categories.first().filter { it.subjectId == 1L }.map { it.id }
        for (id in subjectOneCategories.drop(1)) repository.setCategoryArchived(id, true)
        assertTrue(
            failureOf { repository.setCategoryArchived(subjectOneCategories.first(), true) }
                .contains("至少要保留一个启用类别"),
        )
        repository.setCategoryArchived(subjectOneCategories[1], false)
        val active = repository.categories.first()
            .filter { it.subjectId == 1L && !it.archived }.map { it.id }
        assertEquals(subjectOneCategories[1], active.last())
    }

    @Test
    fun archivingEveryCategoryOfAnArchivedSubjectIsAllowed() = runTest {
        repository.setSubjectArchived(3, true)
        for (category in repository.categories.first().filter { it.subjectId == 3L }) {
            repository.setCategoryArchived(category.id, true)
        }
        val archived = repository.categories.first().filter { it.subjectId == 3L }
        assertTrue(archived.all { it.archived })
    }

    @Test
    fun deleteBlocksPresetsAndAnyReferencedItem() = runTest {
        assertEquals(CatalogRules.PRESET_LOCKED, failureOf { repository.deleteCategory(1) })
        assertEquals(
            CatalogRules.PRESET_LOCKED,
            failureOf { repository.deleteSubject(1) },
        )

        val customId = repository.addSubject("我的科目", ExamType.CIVIL_SERVANT)
        val categoryId = repository.addCategory(customId, "我的类别")
        database.sessionDao().insert(session(categoryId, customId))
        val blocked = failureOf { repository.deleteCategory(categoryId) }
        assertTrue(blocked.contains("1 条记录") && blocked.contains("不能删除"))
        assertTrue(failureOf { repository.deleteSubject(customId) }.contains("1 条记录"))

        // 换一个没有引用的类别：可以删除，且删除后剩余类别重排为 0..n-1。
        val emptyCategory = repository.addCategory(customId, "空类别")
        repository.deleteCategory(emptyCategory)
        assertNull(repository.categories.first().firstOrNull { it.id == emptyCategory })
        val remaining = repository.categories.first().filter { it.subjectId == customId }
        assertEquals(listOf(0), remaining.map { it.sortOrder }.sorted())
    }

    @Test
    fun deletingAnEmptySubjectTakesItsCategoriesWithIt() = runTest {
        val subjectId = repository.addSubject("临时科目", ExamType.CIVIL_SERVANT)
        repository.addCategory(subjectId, "临时类别一")
        repository.addCategory(subjectId, "临时类别二")
        repository.deleteSubject(subjectId)
        assertNull(repository.subjects.first().firstOrNull { it.id == subjectId })
        assertTrue(repository.categories.first().none { it.subjectId == subjectId })
    }

    @Test
    fun emptySubjectAndCategoryStillReportPresetBySeedMapping() = runTest {
        // 直接映射，防止 isPreset 被改成「按名称猜」。
        assertTrue(CategoryEntity(43, 6, "文章写作", sortOrder = 4).toDomain().isPreset)
        assertFalse(CategoryEntity(9_999, 1, "自建").toDomain().isPreset)
    }

    private suspend fun civilServantSubjectIds(): List<Long> =
        repository.subjects.first()
            .filter { it.examType == ExamType.CIVIL_SERVANT && !it.archived }
            .map { it.id }

    private fun session(categoryId: Long, subjectId: Long) = SessionEntity(
        categoryId = categoryId,
        subjectId = subjectId,
        startAt = 1_000,
        endAt = 5_000,
        durationSec = 3,
        totalCount = 3,
        correctCount = 2,
        source = SessionSource.TIMER,
        createdAt = 6_000,
        updatedAt = 6_000,
    )

    private suspend fun failureOf(block: suspend () -> Unit): String {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("应当抛出 IllegalArgumentException，实际：$error", error is IllegalArgumentException)
        return error?.message.orEmpty()
    }
}
