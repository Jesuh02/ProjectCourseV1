package com.example.tareamov.data

import android.content.ContentValues.TAG
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.tareamov.data.dao.PersonaDao
import com.example.tareamov.data.dao.UsuarioDao
import com.example.tareamov.data.dao.VideoDao
import com.example.tareamov.data.dao.TopicDao
import com.example.tareamov.data.dao.ContentItemDao
import com.example.tareamov.data.dao.TaskDao
import com.example.tareamov.data.dao.SubscriptionDao
import com.example.tareamov.data.dao.TaskSubmissionDao
import com.example.tareamov.data.dao.ChatMessageDao
import com.example.tareamov.data.dao.FileContextDao
import com.example.tareamov.data.dao.CourseDao
import com.example.tareamov.data.dao.RolDao
import com.example.tareamov.data.dao.RecursoDao
import com.example.tareamov.data.dao.RolRecursoDao
import com.example.tareamov.data.dao.ProgresoEstudianteDao
import com.example.tareamov.data.dao.VideoLikeDao
import com.example.tareamov.data.dao.VideoCommentDao
import com.example.tareamov.data.entity.Persona
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.data.entity.VideoData
import com.example.tareamov.data.entity.Topic
import com.example.tareamov.data.entity.ContentItem
import com.example.tareamov.data.entity.Task
import com.example.tareamov.data.entity.Subscription
import com.example.tareamov.data.entity.TaskSubmission
import com.example.tareamov.data.entity.ChatMessage
import com.example.tareamov.data.entity.FileContext
import com.example.tareamov.data.entity.Course
import com.example.tareamov.data.entity.Rol
import com.example.tareamov.data.entity.Recurso
import com.example.tareamov.data.entity.RolRecurso
import com.example.tareamov.data.entity.ProgresoEstudiante
import com.example.tareamov.data.entity.VideoLike
import com.example.tareamov.data.entity.UserVideoLike
import com.example.tareamov.data.entity.VideoComment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// In the @Database annotation, add Purchase to the entities list
@Database(
    entities = [
        Persona::class,
        Usuario::class,
        VideoData::class,
        Topic::class,
        ContentItem::class,
        Task::class,
        Subscription::class,
        TaskSubmission::class,
        ChatMessage::class,
        FileContext::class,
        Course::class,
        Rol::class,
        Recurso::class,
        RolRecurso::class,
        ProgresoEstudiante::class,
        VideoLike::class,
        UserVideoLike::class,
        VideoComment::class
    ],
    version = 31, // Updated version to add video_likes, user_video_likes, and video_comments tables
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao
    abstract fun personaDao(): PersonaDao
    abstract fun usuarioDao(): UsuarioDao
    abstract fun topicDao(): TopicDao
    abstract fun contentItemDao(): ContentItemDao
    abstract fun taskDao(): TaskDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun taskSubmissionDao(): TaskSubmissionDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun fileContextDao(): FileContextDao
    abstract fun courseDao(): CourseDao  // Add CourseDao
    abstract fun rolDao(): RolDao  // Add RolDao
    abstract fun recursoDao(): RecursoDao  // Add RecursoDao
    abstract fun rolRecursoDao(): RolRecursoDao  // Add RolRecursoDao
    abstract fun progresoEstudianteDao(): ProgresoEstudianteDao  // Add ProgresoEstudianteDao
    abstract fun videoLikeDao(): VideoLikeDao  // Add VideoLikeDao
    abstract fun videoCommentDao(): VideoCommentDao  // Add VideoCommentDao

    // Métodos para notificar cambios en la base de datos
    fun notifyDataChanged() {
        // No-op
    }

    fun notifyDatabaseChanged() {
        // No-op
    }

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null


        /**
         * Force reset the database by clearing the instance and deleting the database file
         */
        fun resetDatabase(context: Context) {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
                context.deleteDatabase("app_database")
                Log.i(TAG, "Database reset completed")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                // Check if we need to reset the database due to integrity issues
                try {
                    val testDb = Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "app_database"
                    )
                        .allowMainThreadQueries() // Only for this check
                        .build()

                    // Try to open and verify integrity
                    testDb.openHelper.readableDatabase
                    testDb.close()

                } catch (e: Exception) {
                    Log.w(TAG, "Database integrity issue detected, will recreate: ${e.message}")
                    // Delete the problematic database
                    context.deleteDatabase("app_database")
                }

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_database"
                )
                    .fallbackToDestructiveMigration() // This will recreate DB if any migration fails
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                        MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                        MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
                        MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21,
                        MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25,
                        MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29,
                        MIGRATION_29_30, MIGRATION_30_31
                    )
                    .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                    .build()
                INSTANCE = instance

                // Initialize default roles after database creation
                CoroutineScope(Dispatchers.IO).launch {
                    val rolRepository = com.example.tareamov.repository.RolRepository(instance.rolDao(), instance.usuarioDao())
                    rolRepository.initializeDefaultRoles()
                    
                    // Initialize recursos and rol_recursos after roles are created
                    val recursoRepository = com.example.tareamov.repository.RecursoRepository(
                        instance.recursoDao(), 
                        instance.rolRecursoDao()
                    )
                    
                    // Get role IDs for initialization
                    val usuarioRole = instance.rolDao().getRolByNombre("usuario")
                    val adminRole = instance.rolDao().getRolByNombre("admin")
                    
                    if (usuarioRole != null && adminRole != null) {
                        // Initialize recursos and role-resource relationships
                        val databaseInitializer = DatabaseInitializer(recursoRepository)
                        databaseInitializer.initializeDefaultData(usuarioRole.id, adminRole.id)
                        
                        // Verify the initialization
                        val integrityResult = databaseInitializer.verifyDataIntegrity()
                        Log.i(TAG, "Database recursos initialization: ${integrityResult.message}")
                        Log.i(TAG, "Total recursos created: ${integrityResult.totalRecursos}")
                    } else {
                        Log.w(TAG, "Could not find default roles for recursos initialization")
                    }
                }



                instance
            }
        }

        // Define migration from version 1 to 2
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add the esUsuario column to the personas table
                db.execSQL("ALTER TABLE personas ADD COLUMN esUsuario INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Define migration from version 2 to 3
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create the videos table
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS videos (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "username TEXT NOT NULL, " +
                            "description TEXT NOT NULL, " +
                            "title TEXT NOT NULL, " +
                            "videoUriString TEXT, " +
                            "timestamp INTEGER NOT NULL DEFAULT 0)"
                )
            }
        }

        // Define migration from version 3 to 4
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add localFilePath column to videos table
                db.execSQL("ALTER TABLE videos ADD COLUMN localFilePath TEXT")
            }
        }

        // Define migration from version 4 to 5 (adding Topic and ContentItem tables)
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create topics table with proper foreign key
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `topics` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `courseId` INTEGER NOT NULL,
                    `name` TEXT NOT NULL,
                    `description` TEXT NOT NULL,
                    `orderIndex` INTEGER NOT NULL,
                    FOREIGN KEY(`courseId`) REFERENCES `videos`(`id`) ON DELETE CASCADE
                )"""
                )

                // Create index on courseId in topics table
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_topics_courseId` ON `topics` (`courseId`)")

                // Create content_items table
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `content_items` ("
                            + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                            + "`topicId` INTEGER NOT NULL, "
                            + "`name` TEXT NOT NULL, "
                            + "`uriString` TEXT NOT NULL, "
                            + "`contentType` TEXT NOT NULL, "
                            + "`orderIndex` INTEGER NOT NULL, "
                            + "FOREIGN KEY(`topicId`) REFERENCES `topics`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )

                // Create index on topicId in content_items table
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_content_items_topicId` ON `content_items` (`topicId`)")
            }
        }

        // Define migration from version 5 to 6 (adding Task table)
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create tasks table - Corrected FK and Index
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `tasks` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `topicId` INTEGER NOT NULL, -- Corrected from courseId
                    `name` TEXT NOT NULL,
                    `description` TEXT, -- Made nullable in previous step
                    `orderIndex` INTEGER NOT NULL,
                    FOREIGN KEY(`topicId`) REFERENCES `topics`(`id`) ON DELETE CASCADE -- Corrected reference from videos(id)
                )"""
                )

                // Create index on topicId in tasks table - Corrected index name and column
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_topicId` ON `tasks` (`topicId`)")
            }
        }

        // Define migration from version 6 to 7 (Empty migration for now, add SQL if schema changed)
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add necessary SQL statements here if the schema actually changed between version 6 and 7.
                // For example: db.execSQL("ALTER TABLE your_table ADD COLUMN new_column TEXT")
                Log.i(TAG, "Executing migration from 6 to 7")
            }
        }

        // Define migration from version 7 to 8 (Empty migration to force schema update)
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No schema changes needed, but this forces Room to re-check the schema.
                Log.i(TAG, "Executing migration from 7 to 8")
            }
        }

        // Define migration from version 8 to 9 (Fix tasks table schema)
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Drop the existing tasks table if it exists
                db.execSQL("DROP TABLE IF EXISTS `tasks`")

                // Recreate the tasks table with the correct schema
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `tasks` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `topicId` INTEGER NOT NULL,
                    `name` TEXT NOT NULL,
                    `description` TEXT,
                    `orderIndex` INTEGER NOT NULL,
                    FOREIGN KEY(`topicId`) REFERENCES `topics`(`id`) ON DELETE CASCADE
                )"""
                )

                // Create index on topicId in tasks table
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_topicId` ON `tasks` (`topicId`)")

                Log.i(TAG, "Executing migration from 8 to 9: Fixed tasks table schema")
            }
        }

        // Define migration from version 9 to 10 (Fix foreign key constraints)
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Enable foreign key support
                db.execSQL("PRAGMA foreign_keys = ON")

                // Create a temporary table for topics without foreign key constraints
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `topics_temp` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `courseId` INTEGER NOT NULL,
                    `name` TEXT NOT NULL,
                    `description` TEXT NOT NULL,
                    `orderIndex` INTEGER NOT NULL
                )"""
                )

                // Copy data from topics to topics_temp
                db.execSQL("INSERT OR IGNORE INTO topics_temp SELECT * FROM topics")

                // Drop the original topics table
                db.execSQL("DROP TABLE IF EXISTS topics")

                // Recreate the topics table with proper foreign key constraints
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `topics` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `courseId` INTEGER NOT NULL,
                    `name` TEXT NOT NULL,
                    `description` TEXT NOT NULL,
                    `orderIndex` INTEGER NOT NULL,
                    FOREIGN KEY(`courseId`) REFERENCES `videos`(`id`) ON DELETE CASCADE
                )"""
                )

                // Create index on courseId in topics table
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_topics_courseId` ON `topics` (`courseId`)")

                // Copy data back from topics_temp to topics
                db.execSQL("INSERT OR IGNORE INTO topics SELECT * FROM topics_temp")

                // Drop the temporary table
                db.execSQL("DROP TABLE IF EXISTS topics_temp")

                // Create a default course if none exists
                db.execSQL(
                    """INSERT OR IGNORE INTO videos (id, username, description, title, timestamp)
                   SELECT 1, 'default_user', 'Curso predeterminado', 'Curso predeterminado', ${System.currentTimeMillis()}
                   WHERE NOT EXISTS (SELECT 1 FROM videos LIMIT 1)"""
                )

                Log.i(TAG, "Executing migration from 9 to 10: Fixed foreign key constraints")
            }
        }

        // Define migration from version 10 to 11 (Add Subscription table)
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `subscriptions` (
                    `subscriberUsername` TEXT NOT NULL,
                    `creatorUsername` TEXT NOT NULL,
                    `subscriptionDate` INTEGER NOT NULL,
                    PRIMARY KEY(`subscriberUsername`, `creatorUsername`)
                )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_subscriptions_subscriberUsername` ON `subscriptions` (`subscriberUsername`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_subscriptions_creatorUsername` ON `subscriptions` (`creatorUsername`)")
            }
        }

        // Update the MIGRATION_11_12 to properly handle the subscriptions table
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Drop the indices that are causing the mismatch
                db.execSQL("DROP INDEX IF EXISTS index_subscriptions_creatorUsername")
                db.execSQL("DROP INDEX IF EXISTS index_subscriptions_subscriberUsername")

                // Create a temporary table with the correct structure
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `subscriptions_temp` (
                    `subscriberUsername` TEXT NOT NULL,
                    `creatorUsername` TEXT NOT NULL,
                    `subscriptionDate` INTEGER NOT NULL,
                    PRIMARY KEY(`subscriberUsername`, `creatorUsername`)
                )"""
                )

                // Copy data from the old table to the new one
                db.execSQL("INSERT OR IGNORE INTO subscriptions_temp SELECT subscriberUsername, creatorUsername, subscriptionDate FROM subscriptions")

                // Drop the old table
                db.execSQL("DROP TABLE subscriptions")

                // Rename the new table to the original name
                db.execSQL("ALTER TABLE subscriptions_temp RENAME TO subscriptions")

                // Create the indices that match the entity definition
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_subscriptions_subscriberUsername` ON `subscriptions` (`subscriberUsername`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_subscriptions_creatorUsername` ON `subscriptions` (`creatorUsername`)")

                Log.i(TAG, "Migration 11 to 12 completed: Fixed subscriptions table schema")
            }
        }

        // Add migration from version 12 to 13
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Ensure indices exist for subscriptions table
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_subscriptions_subscriberUsername` ON `subscriptions` (`subscriberUsername`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_subscriptions_creatorUsername` ON `subscriptions` (`creatorUsername`)")

                Log.i(TAG, "Executing migration from 12 to 13: Ensuring subscription indices exist")
            }
        }

        // Add migration from version 13 to 14
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Ensure indices exist for subscriptions table (from original migration)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_subscriptions_subscriberUsername` ON `subscriptions` (`subscriberUsername`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_subscriptions_creatorUsername` ON `subscriptions` (`creatorUsername`)")

                // --- Start of changes for content_items ---
                // Recreate content_items table to match the expected schema (add taskId, FK, index, adjust nullability)
                // SQLite requires table recreation to add foreign keys or modify constraints significantly.

                // a. Create a temporary table with the new structure matching the ContentItem entity
                db.execSQL("""
                    CREATE TABLE `content_items_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `topicId` INTEGER NOT NULL,
                        `name` TEXT, -- Adjusted to nullable based on 'Expected' schema in error
                        `uriString` TEXT NOT NULL,
                        `contentType` TEXT NOT NULL,
                        `orderIndex` INTEGER, -- Adjusted to nullable based on 'Expected' schema in error
                        `taskId` INTEGER, -- New column based on 'Expected' schema
                        FOREIGN KEY(`topicId`) REFERENCES `topics`(`id`) ON DELETE CASCADE,
                        FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON DELETE CASCADE -- New FK based on 'Expected' schema
                    )
                """)

                // b. Copy data from the old table to the new table.
                //    Select specific columns to avoid issues if the old table schema differs.
                //    Set the new taskId column to NULL for existing rows.
                db.execSQL("""
                    INSERT INTO `content_items_new` (`id`, `topicId`, `name`, `uriString`, `contentType`, `orderIndex`, `taskId`)
                    SELECT `id`, `topicId`, `name`, `uriString`, `contentType`, `orderIndex`, NULL
                    FROM `content_items`
                """)

                // c. Drop the old content_items table
                db.execSQL("DROP TABLE `content_items`")

                // d. Rename the new temporary table to the original name
                db.execSQL("ALTER TABLE `content_items_new` RENAME TO `content_items`")

                // e. Recreate indices for the new table structure
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_content_items_topicId` ON `content_items` (`topicId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_content_items_taskId` ON `content_items` (`taskId`)") // New index for taskId

                // --- End of changes for content_items ---

                Log.i(TAG, "Migration 13 to 14: Ensured subscription indices exist and updated content_items schema.")
            }
        }

        // Add migration from version 14 to 15 (Add rol column to usuarios table)
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add the rol column to the usuarios table with default value "usuario"
                db.execSQL("ALTER TABLE usuarios ADD COLUMN rol TEXT NOT NULL DEFAULT 'usuario'")

                Log.i(TAG, "Migration 14 to 15: Added rol column to usuarios table")
            }
        }

        // Add migration from version 15 to 16 (Add TaskSubmission table)
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create TaskSubmission table
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `task_submissions` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `taskId` INTEGER NOT NULL,
                    `studentUsername` TEXT NOT NULL,
                    `submissionDate` INTEGER NOT NULL,
                    `fileUri` TEXT NOT NULL,
                    `fileName` TEXT NOT NULL,
                    `grade` REAL,
                    `feedback` TEXT,
                    FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON DELETE CASCADE
                )"""
                )

                // Create indices for task_submissions table
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_submissions_taskId` ON `task_submissions` (`taskId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_submissions_studentUsername` ON `task_submissions` (`studentUsername`)")

                Log.i(TAG, "Migration 15 to 16: Added task_submissions table")
            }
        }

        // Define migration from version 16 to 17 (Update user roles)
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Update any existing "estudiante" roles to "usuario"
                db.execSQL("UPDATE usuarios SET rol = 'usuario' WHERE rol = 'estudiante'")
                Log.i(TAG, "Migration 16 to 17 completed: Updated user roles from 'estudiante' to 'usuario'")
            }
        }

        // Migration 17 -> 18: Replace studentUsername (TEXT) with studentId (INTEGER) in task_submissions
        // Also create purchases table and add isPaid column to videos as part of the same version bump
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create new table with studentId column (nullable initially)
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `task_submissions_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `taskId` INTEGER NOT NULL,
                    `studentId` INTEGER,
                    `submissionDate` INTEGER NOT NULL,
                    `fileUri` TEXT NOT NULL,
                    `fileName` TEXT NOT NULL,
                    `grade` REAL,
                    `feedback` TEXT,
                    FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON DELETE CASCADE,
                    FOREIGN KEY(`studentId`) REFERENCES `usuarios`(`id`) ON DELETE SET NULL
                )"""
                )

                // Copy data from old table, resolving studentUsername -> studentId via usuarios table
                db.execSQL(
                    """INSERT INTO task_submissions_new (id, taskId, studentId, submissionDate, fileUri, fileName, grade, feedback)
                    SELECT ts.id, ts.taskId,
                      (SELECT u.id FROM usuarios u WHERE lower(u.usuario) = lower(ts.studentUsername) LIMIT 1) as studentId,
                      ts.submissionDate, ts.fileUri, ts.fileName, ts.grade, ts.feedback
                    FROM task_submissions ts"""
                )

                // Drop old table and rename new
                db.execSQL("DROP TABLE IF EXISTS task_submissions")
                db.execSQL("ALTER TABLE task_submissions_new RENAME TO task_submissions")

                // Recreate indices for task_submissions
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_submissions_taskId` ON `task_submissions` (`taskId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_submissions_studentId` ON `task_submissions` (`studentId`)")

                // Create purchases table as part of this migration
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `purchases` (
                    `username` TEXT NOT NULL,
                    `courseId` INTEGER NOT NULL,
                    `purchaseDate` INTEGER NOT NULL,
                    PRIMARY KEY(`username`, `courseId`)
                )"""
                )

                // Create index on courseId in purchases table
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchases_courseId` ON `purchases` (`courseId`)")

                // Add isPaid column to videos table if it doesn't exist
                try {
                    db.execSQL("ALTER TABLE videos ADD COLUMN isPaid INTEGER NOT NULL DEFAULT 0")
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding isPaid column to videos table: ${e.message}")
                    // Column might already exist, which is fine
                }

                Log.i(TAG, "Migration 17 to 18: Replaced studentUsername with studentId and added purchases/isPaid changes")
            }
        }

        // Define migration from version 18 to 19 (Add thumbnailUri column to videos table)
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE videos ADD COLUMN thumbnailUri TEXT")
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding thumbnailUri column to videos table: ${e.message}")
                }
                Log.i(TAG, "Migration 18 to 19 completed: Added thumbnailUri column to videos table")
            }
        }

        // Define migration from version 19 to 20 (Add price columns)
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    // Add price column to purchases table
                    db.execSQL("ALTER TABLE purchases ADD COLUMN price REAL")

                    // Add price column to videos table
                    db.execSQL("ALTER TABLE videos ADD COLUMN price REAL")

                    Log.i(TAG, "Migration 19 to 20 completed: Added price column to purchases and videos tables")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in migration 19 to 20: ${e.message}")
                }
            }
        }

        // Migration 20 to 21: Add chat_messages and file_contexts tables if they don't exist
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    // Create chat_messages table if it doesn't exist
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `chat_messages` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `message` TEXT NOT NULL,
                            `isFromUser` INTEGER NOT NULL,
                            `timestamp` INTEGER NOT NULL,
                            `sessionId` TEXT
                        )
                    """)

                    // Create file_contexts table if it doesn't exist
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `file_contexts` (
                            `submissionId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `fileName` TEXT NOT NULL,
                            `fileType` TEXT NOT NULL,
                            `fileContent` TEXT NOT NULL,
                            `contentSummary` TEXT NOT NULL,
                            `timestamp` INTEGER NOT NULL
                        )
                    """)

                    Log.i(TAG, "Migration 20 to 21 completed: Added chat_messages and file_contexts tables")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in migration 20 to 21: ${e.message}")
                }
            }
        }

        // Migration 21 to 22: Add calification columns to chat_messages table
        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    // Add new columns to chat_messages table
                    db.execSQL("ALTER TABLE chat_messages ADD COLUMN hasCalification INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE chat_messages ADD COLUMN calificationValue TEXT")
                    db.execSQL("ALTER TABLE chat_messages ADD COLUMN calificationAdded INTEGER NOT NULL DEFAULT 0")

                    Log.i(TAG, "Migration 21 to 22 completed: Added calification columns to chat_messages table")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in migration 21 to 22: ${e.message}")
                }
            }
        }

        // Migration 22 to 23: Ensure all tables exist with correct schema
        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    // Recreate chat_messages table with complete schema to avoid integrity issues
                    db.execSQL("DROP TABLE IF EXISTS chat_messages_temp")

                    // Create new table with all columns
                    db.execSQL("""
                        CREATE TABLE chat_messages_temp (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            message TEXT NOT NULL,
                            isFromUser INTEGER NOT NULL,
                            timestamp INTEGER NOT NULL,
                            sessionId TEXT,
                            hasCalification INTEGER NOT NULL DEFAULT 0,
                            calificationValue TEXT,
                            calificationAdded INTEGER NOT NULL DEFAULT 0
                        )
                    """)

                    // Copy existing data
                    db.execSQL("""
                        INSERT INTO chat_messages_temp (id, message, isFromUser, timestamp, sessionId, hasCalification, calificationValue, calificationAdded)
                        SELECT id, message, isFromUser, timestamp, 
                               COALESCE(sessionId, NULL),
                               COALESCE(hasCalification, 0),
                               COALESCE(calificationValue, NULL),
                               COALESCE(calificationAdded, 0)
                        FROM chat_messages
                    """)

                    // Replace old table
                    db.execSQL("DROP TABLE chat_messages")
                    db.execSQL("ALTER TABLE chat_messages_temp RENAME TO chat_messages")

                    Log.i(TAG, "Migration 22 to 23 completed: Ensured chat_messages table integrity")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in migration 22 to 23: ${e.message}")
                }
            }
        }

        // Migration 23 to 24: Add courses table
        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    // Create courses table
                    db.execSQL("""
                        CREATE TABLE courses (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            title TEXT NOT NULL,
                            description TEXT NOT NULL,
                            creatorUsername TEXT NOT NULL,
                            thumbnailUri TEXT,
                            videoUri TEXT,
                            localFilePath TEXT,
                            duration TEXT,
                            category TEXT,
                            price REAL NOT NULL DEFAULT 0.0,
                            isPremium INTEGER NOT NULL DEFAULT 0,
                            isPublished INTEGER NOT NULL DEFAULT 1,
                            creationDate TEXT NOT NULL DEFAULT '',
                            lastModifiedDate TEXT NOT NULL DEFAULT '',
                            enrollmentCount INTEGER NOT NULL DEFAULT 0,
                            rating REAL NOT NULL DEFAULT 0.0,
                            tags TEXT,
                            timestamp INTEGER NOT NULL DEFAULT 0
                        )
                    """)

                    // Create index on creatorUsername for performance
                    db.execSQL("CREATE INDEX index_courses_creatorUsername ON courses (creatorUsername)")

                    // Create index on category for filtering
                    db.execSQL("CREATE INDEX index_courses_category ON courses (category)")

                    Log.i(TAG, "Migration 23 to 24 completed: Added courses table")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in migration 23 to 24: ${e.message}")
                }
            }
        }

        // Migration 24 to 25: Add roles table and update usuarios table
        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    // Create roles table
                    db.execSQL("""
                        CREATE TABLE roles (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            nombre TEXT NOT NULL,
                            nivel REAL NOT NULL,
                            `default` INTEGER NOT NULL DEFAULT 0
                        )
                    """)

                    // Insert default roles
                    db.execSQL("INSERT INTO roles (nombre, nivel, `default`) VALUES ('usuario', 1.0, 1)")
                    db.execSQL("INSERT INTO roles (nombre, nivel, `default`) VALUES ('admin', 2.0, 0)")

                    // Create new usuarios table with rol_id instead of rol
                    db.execSQL("""
                        CREATE TABLE usuarios_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            usuario TEXT NOT NULL DEFAULT '',
                            contrasena TEXT NOT NULL DEFAULT '',
                            persona_id INTEGER NOT NULL DEFAULT 0,
                            rol_id INTEGER NOT NULL DEFAULT 1,
                            FOREIGN KEY (persona_id) REFERENCES personas (id) ON DELETE CASCADE,
                            FOREIGN KEY (rol_id) REFERENCES roles (id) ON DELETE CASCADE
                        )
                    """)

                    // Copy data from old table, mapping rol strings to rol_ids
                    db.execSQL("""
                        INSERT INTO usuarios_new (id, usuario, contrasena, persona_id, rol_id)
                        SELECT 
                            id, 
                            usuario, 
                            contrasena, 
                            persona_id,
                            CASE 
                                WHEN rol = 'admin' THEN 2
                                ELSE 1
                            END
                        FROM usuarios
                    """)

                    // Drop old table and rename new table
                    db.execSQL("DROP TABLE usuarios")
                    db.execSQL("ALTER TABLE usuarios_new RENAME TO usuarios")

                    // Create indices
                    db.execSQL("CREATE INDEX index_usuarios_persona_id ON usuarios (persona_id)")
                    db.execSQL("CREATE INDEX index_usuarios_rol_id ON usuarios (rol_id)")

                    Log.i(TAG, "Migration 24 to 25 completed: Added roles table and updated usuarios table")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in migration 24 to 25: ${e.message}")
                    throw e
                }
            }
        }

        // Migration 25 to 26: Add recursos and rol_recursos tables
        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    // Create recursos table
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `recursos` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `nombre` TEXT NOT NULL,
                            `icono` TEXT NOT NULL,
                            `orden` INTEGER NOT NULL,
                            `padreId` INTEGER,
                            `interfaz` TEXT,
                            FOREIGN KEY(`padreId`) REFERENCES `recursos`(`id`) ON DELETE CASCADE
                        )
                    """)

                    // Create rol_recursos table
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `rol_recursos` (
                            `rolId` INTEGER NOT NULL,
                            `recursoId` INTEGER NOT NULL,
                            PRIMARY KEY(`rolId`, `recursoId`),
                            FOREIGN KEY(`rolId`) REFERENCES `roles`(`id`) ON DELETE CASCADE,
                            FOREIGN KEY(`recursoId`) REFERENCES `recursos`(`id`) ON DELETE CASCADE
                        )
                    """)

                    // Create indices
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_recursos_padreId` ON `recursos` (`padreId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_rol_recursos_rolId` ON `rol_recursos` (`rolId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_rol_recursos_recursoId` ON `rol_recursos` (`recursoId`)")

                    // Insert default recursos
                    insertDefaultRecursos(db)

                    Log.i(TAG, "Migration 25 to 26 completed: Added recursos and rol_recursos tables")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in migration 25 to 26: ${e.message}")
                    throw e
                }
            }
            
            private fun insertDefaultRecursos(db: SupportSQLiteDatabase) {
                // Insert main navigation resource
                db.execSQL("""
                    INSERT OR IGNORE INTO recursos (id, nombre, icono, orden, padreId, interfaz) 
                    VALUES (1, 'Navegación', 'navigation_menu', 1, NULL, 'BottomNavigation')
                """)
                
                // Insert navigation sub-resources
                db.execSQL("""
                    INSERT OR IGNORE INTO recursos (id, nombre, icono, orden, padreId, interfaz) VALUES
                    (2, 'Inicio', 'homeButton', 1, 1, 'VideoHomeFragment'),
                    (3, 'Explorar', 'exploreButton', 2, 1, 'ExploreFragment'),
                    (4, 'Perfil', 'profileButton', 3, 1, 'ProfileFragment'),
                    (5, 'Notificaciones', 'notificationsButton', 4, 1, 'NotificacionesFragment')
                """)
                
                // Insert admin panel resource
                db.execSQL("""
                    INSERT OR IGNORE INTO recursos (id, nombre, icono, orden, padreId, interfaz) 
                    VALUES (6, 'Panel de Administración', 'admin_panel', 2, NULL, 'AdminPanel')
                """)
                
                // Insert admin sub-resources for each interface
                db.execSQL("""
                    INSERT OR IGNORE INTO recursos (id, nombre, icono, orden, padreId, interfaz) VALUES
                    (7, 'Botón Admin - Home', 'goToAdminButton', 1, 6, 'VideoHomeFragment'),
                    (8, 'Botón Admin - Explore', 'goToAdminButton', 2, 6, 'ExploreFragment'),
                    (9, 'Botón Admin - Profile', 'goToAdminButton', 3, 6, 'ProfileFragment'),
                    (10, 'Botón Admin - User Profile', 'goToAdminButton', 4, 6, 'UserProfileViewFragment'),
                    (11, 'Botón Admin - Notificaciones', 'goToAdminButton', 5, 6, 'NotificacionesFragment'),
                    (12, 'Base de Datos', 'databaseOrbitButton', 6, 6, 'VideoHomeFragment')
                """)
                
                // Assign resources to roles
                // Usuario role (assuming id = 1) gets navigation resources only
                db.execSQL("""
                    INSERT OR IGNORE INTO rol_recursos (rolId, recursoId) VALUES
                    (1, 1), (1, 2), (1, 3), (1, 4), (1, 5)
                """)
                
                // Admin role (assuming id = 2) gets all resources
                db.execSQL("""
                    INSERT OR IGNORE INTO rol_recursos (rolId, recursoId) VALUES
                    (2, 1), (2, 2), (2, 3), (2, 4), (2, 5),
                    (2, 6), (2, 7), (2, 8), (2, 9), (2, 10), (2, 11), (2, 12)
                """)
            }
        }

        // Migration 26 to 27: Remove purchases table
        private val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    // Drop the purchases table
                    db.execSQL("DROP TABLE IF EXISTS purchases")
                    
                    // Remove isPaid and price columns from videos table if they exist
                    // SQLite doesn't support DROP COLUMN directly, so we need to recreate the table
                    
                    // Create new videos table without isPaid and price columns
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS videos_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            username TEXT NOT NULL,
                            description TEXT NOT NULL,
                            title TEXT NOT NULL,
                            videoUriString TEXT,
                            timestamp INTEGER NOT NULL,
                            localFilePath TEXT,
                            thumbnailUri TEXT
                        )
                    """)
                    
                    // Copy data from old table to new table (excluding isPaid and price columns)
                    db.execSQL("""
                        INSERT INTO videos_new (id, username, description, title, videoUriString, timestamp, localFilePath, thumbnailUri)
                        SELECT id, username, description, title, videoUriString, timestamp, localFilePath, thumbnailUri
                        FROM videos
                    """)
                    
                    // Drop old table
                    db.execSQL("DROP TABLE videos")
                    
                    // Rename new table to original name
                    db.execSQL("ALTER TABLE videos_new RENAME TO videos")
                    
                    Log.i(TAG, "Migration 26 to 27 completed: Removed purchases table and cleaned up videos table")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in migration 26 to 27", e)
                }
            }
        }

        // Migration 27 to 28: Update role names from "estudiante" to "usuario"
        private val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    // Update roles table to change "estudiante" to "usuario"
                    db.execSQL("UPDATE roles SET nombre = 'usuario' WHERE nombre = 'estudiante'")
                    
                    Log.i(TAG, "Migration 27 to 28 completed: Updated role name from 'estudiante' to 'usuario'")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in migration 27 to 28", e)
                }
            }
        }

        // Migration 28 to 29: Add ProgresoEstudiante table
        private val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS progreso_estudiante (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            usuario_id INTEGER NOT NULL,
                            video_id INTEGER NOT NULL,
                            visto INTEGER NOT NULL DEFAULT 0,
                            fecha_visto INTEGER,
                            FOREIGN KEY (usuario_id) REFERENCES usuarios(id) ON DELETE CASCADE,
                            FOREIGN KEY (video_id) REFERENCES videos(id) ON DELETE CASCADE
                        )
                    """)
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_progreso_estudiante_usuario_id ON progreso_estudiante(usuario_id)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_progreso_estudiante_video_id ON progreso_estudiante(video_id)")
                    Log.i(TAG, "Migration 28 to 29 completed: Added progreso_estudiante table")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in migration 28 to 29", e)
                }
            }
        }

        // Migration 29 to 30: Add email and avatar columns to usuarios table for Google Sign-In
        private val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    // Add email column
                    db.execSQL("ALTER TABLE usuarios ADD COLUMN email TEXT")
                    // Add avatar column
                    db.execSQL("ALTER TABLE usuarios ADD COLUMN avatar TEXT")
                    
                    Log.i(TAG, "Migration 29 to 30 completed: Added email and avatar columns to usuarios table")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in migration 29 to 30", e)
                }
            }
        }

        // Migration 30 to 31: Add video_likes, user_video_likes, and video_comments tables
        private val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    // Create video_likes table
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `video_likes` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `video_id` INTEGER NOT NULL,
                            `like_count` INTEGER NOT NULL DEFAULT 0,
                            FOREIGN KEY(`video_id`) REFERENCES `videos`(`id`) ON DELETE CASCADE
                        )
                    """)
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_video_likes_video_id` ON `video_likes` (`video_id`)")
                    
                    // Create user_video_likes table (to track which user liked which video)
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `user_video_likes` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `video_id` INTEGER NOT NULL,
                            `usuario_id` INTEGER NOT NULL,
                            `created_at` TEXT,
                            FOREIGN KEY(`video_id`) REFERENCES `videos`(`id`) ON DELETE CASCADE,
                            FOREIGN KEY(`usuario_id`) REFERENCES `usuarios`(`id`) ON DELETE CASCADE
                        )
                    """)
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_video_likes_video_id` ON `user_video_likes` (`video_id`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_video_likes_usuario_id` ON `user_video_likes` (`usuario_id`)")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_user_video_likes_video_id_usuario_id` ON `user_video_likes` (`video_id`, `usuario_id`)")
                    
                    // Create video_comments table
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `video_comments` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `video_id` INTEGER NOT NULL,
                            `usuario_id` INTEGER NOT NULL,
                            `comment` TEXT NOT NULL,
                            `created_at` TEXT,
                            FOREIGN KEY(`video_id`) REFERENCES `videos`(`id`) ON DELETE CASCADE,
                            FOREIGN KEY(`usuario_id`) REFERENCES `usuarios`(`id`) ON DELETE CASCADE
                        )
                    """)
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_video_comments_video_id` ON `video_comments` (`video_id`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_video_comments_usuario_id` ON `video_comments` (`usuario_id`)")
                    
                    Log.i(TAG, "Migration 30 to 31 completed: Added video_likes, user_video_likes, and video_comments tables")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in migration 30 to 31", e)
                }
            }
        }
    }
}