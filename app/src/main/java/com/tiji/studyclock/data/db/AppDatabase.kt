package com.tiji.studyclock.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tiji.studyclock.data.seed.CategorySeed

@Database(
    entities = [
        SubjectEntity::class, CategoryEntity::class, SessionEntity::class,
        PrescriptionEntity::class, PrescriptionResultEntity::class, ExamPlanEntity::class,
        WrongBatchEntity::class, ReviewAttemptEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun sessionDao(): SessionDao
    abstract fun examPlanDao(): ExamPlanDao
    abstract fun prescriptionDao(): PrescriptionDao
    abstract fun backupDao(): BackupDao
    abstract fun wrongBatchDao(): WrongBatchDao
    abstract fun reviewAttemptDao(): ReviewAttemptDao

    companion object {
        const val DATABASE_NAME = "tiji.db"

        /**
         * v1 → v2：考试目标增加类型。旧数据的类型记为 OTHER，名称保持原样，不做任何重写。
         * 这是本项目第一条真实迁移，必须保留给真实用户库使用。
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exam_target ADD COLUMN examType TEXT NOT NULL DEFAULT 'OTHER'")
            }
        }

        /**
         * v2 → v3：考试类型真正生效。
         * - 科目增加 examType，并把“科目名全局唯一”改为“同考试内唯一”（否则四六级与雅思不能都有“听力”）。
         * - 记录增加 examType 并按科目回填，切换考试时不必联表。
         * - 单行 exam_target 升级为按类型分行的 exam_plan，原计划完整搬到自己的类型下。
         * - 为其余考试补齐预置科目/类别，保证切换过去就能直接开练。
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // subject：加列 + 换唯一索引（不重建表，避免触发外键级联检查）
                db.execSQL("DROP INDEX IF EXISTS index_subject_name")
                db.execSQL("ALTER TABLE subject ADD COLUMN examType TEXT NOT NULL DEFAULT 'CIVIL_SERVANT'")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_subject_examType_name ON subject(examType, name)")

                // session：加列并按科目回填，再建组合索引
                db.execSQL("ALTER TABLE session ADD COLUMN examType TEXT NOT NULL DEFAULT 'CIVIL_SERVANT'")
                db.execSQL(
                    "UPDATE session SET examType = COALESCE(" +
                        "(SELECT examType FROM subject WHERE subject.id = session.subjectId), 'CIVIL_SERVANT')",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_session_examType_startAt ON session(examType, startAt)")

                // exam_target → exam_plan：每个考试类型各自一份计划
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS exam_plan (" +
                        "examType TEXT NOT NULL, name TEXT NOT NULL, examDate TEXT, targetScore INTEGER, " +
                        "PRIMARY KEY(examType))",
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO exam_plan (examType, name, examDate, targetScore) " +
                        "SELECT examType, name, examDate, targetScore FROM exam_target",
                )
                db.execSQL("DROP TABLE exam_target")

                insertExtraCatalog(db)
            }
        }

        /**
         * v3 → v4：记录增加错题关键词（可空，`|` 分隔）。只加列，不重建表；
         * 旧记录保持 NULL，表示当时没有填写，不会被当成“全对”。
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE session ADD COLUMN wrongTags TEXT")
            }
        }

        /**
         * v4 → v5：计分 + 错题复做（一次性，D25）。
         * 只加列、加表、加索引与类别默认值；不重建业务表、不改写历史记录、不回填历史错题袋（D18）。
         * 新表 DDL 与索引名必须与实体注解一致，否则 Room 的 TableInfo 校验会拒绝升级。
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE session ADD COLUMN mode TEXT NOT NULL DEFAULT 'COUNT'")
                db.execSQL("ALTER TABLE session ADD COLUMN kind TEXT NOT NULL DEFAULT 'NORMAL'")
                db.execSQL("ALTER TABLE session ADD COLUMN scoreTenths INTEGER")
                db.execSQL("ALTER TABLE session ADD COLUMN maxScoreTenths INTEGER")
                db.execSQL("ALTER TABLE session ADD COLUMN scoreLabel TEXT")
                db.execSQL("ALTER TABLE session ADD COLUMN batchId INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_session_mode_kind_startAt ON session(mode, kind, startAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_session_batchId ON session(batchId)")

                db.execSQL("ALTER TABLE category ADD COLUMN defaultMode TEXT NOT NULL DEFAULT 'COUNT'")
                db.execSQL("ALTER TABLE category ADD COLUMN defaultMaxScoreTenths INTEGER")
                db.execSQL("ALTER TABLE exam_plan ADD COLUMN targetMaxScoreTenths INTEGER")
                db.execSQL("ALTER TABLE prescription ADD COLUMN batchId INTEGER")
                applyScoreDefaultModes(db)

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS wrong_batch (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "originSessionId INTEGER NOT NULL, " +
                        "categoryId INTEGER NOT NULL, " +
                        "subjectId INTEGER NOT NULL, " +
                        "examType TEXT NOT NULL DEFAULT 'CIVIL_SERVANT', " +
                        "wrongCount INTEGER NOT NULL, " +
                        "wrongTags TEXT, " +
                        "note TEXT, " +
                        "originDate TEXT NOT NULL, " +
                        "archived INTEGER NOT NULL, " +
                        "backfilled INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "FOREIGN KEY(originSessionId) REFERENCES session(id) ON UPDATE RESTRICT ON DELETE RESTRICT, " +
                        "FOREIGN KEY(categoryId) REFERENCES category(id) ON UPDATE RESTRICT ON DELETE RESTRICT" +
                        ")",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_wrong_batch_originSessionId ON wrong_batch(originSessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_wrong_batch_exam_category ON wrong_batch(examType, categoryId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_wrong_batch_categoryId ON wrong_batch(categoryId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_wrong_batch_originDate ON wrong_batch(originDate)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS review_attempt (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "batchId INTEGER NOT NULL, " +
                        "sessionId INTEGER NOT NULL, " +
                        "attemptedCount INTEGER NOT NULL, " +
                        "passedCount INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "FOREIGN KEY(batchId) REFERENCES wrong_batch(id) ON UPDATE RESTRICT ON DELETE RESTRICT, " +
                        "FOREIGN KEY(sessionId) REFERENCES session(id) ON UPDATE RESTRICT ON DELETE RESTRICT" +
                        ")",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_review_attempt_sessionId ON review_attempt(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_review_attempt_batch ON review_attempt(batchId, createdAt)")
            }
        }

        /** 主观题类别的默认模式；默认值只是预填建议，用户随时可改（D23/D24）。 */
        private const val SCORE_CATEGORY_IDS =
            "39,40,41,42,43,1015,1131,1141,1214,1322,1431,1432,1441,1442,1443"

        private fun applyScoreDefaultModes(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE category SET defaultMode = 'SCORE' WHERE id IN ($SCORE_CATEGORY_IDS)")
        }

        private fun insertExtraCatalog(db: SupportSQLiteDatabase) {
            CategorySeed.extraSubjects.forEach { subject ->
                db.execSQL(
                    "INSERT OR IGNORE INTO subject (id, name, colorHex, sortOrder, archived, examType) VALUES (?, ?, ?, ?, 0, ?)",
                    arrayOf<Any?>(subject.id, subject.name, subject.colorHex, subject.sortOrder, subject.examType.key),
                )
            }
            CategorySeed.extraCategories.forEach { category ->
                db.execSQL(
                    "INSERT OR IGNORE INTO category (id, subjectId, name, groupName, sortOrder, archived) VALUES (?, ?, ?, ?, ?, 0)",
                    arrayOf<Any?>(category.id, category.subjectId, category.name, category.groupName, category.sortOrder),
                )
            }
        }

        // Room's persisted creation/version is the seed marker: never seed from onOpen.
        val SEED_CALLBACK: Callback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                db.beginTransaction()
                try {
                    CategorySeed.subjects.forEach { subject ->
                        db.execSQL(
                            "INSERT OR IGNORE INTO subject (id, name, colorHex, sortOrder, archived, examType) VALUES (?, ?, ?, ?, 0, ?)",
                            arrayOf<Any?>(subject.id, subject.name, subject.colorHex, subject.sortOrder, subject.examType.key),
                        )
                    }
                    CategorySeed.categories.forEach { category ->
                        db.execSQL(
                            "INSERT OR IGNORE INTO category (id, subjectId, name, groupName, sortOrder, archived) VALUES (?, ?, ?, ?, ?, 0)",
                            arrayOf<Any?>(category.id, category.subjectId, category.name, category.groupName, category.sortOrder),
                        )
                    }
                    insertExtraCatalog(db)
                    applyScoreDefaultModes(db)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
    }
}
