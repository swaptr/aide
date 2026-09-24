package com.sabreware.aide.feature.tasks.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.sabreware.aide.core.common.di.IO
import kotlinx.coroutines.Dispatchers

/**
 * The Tasks feature's own Room database — **Android-only**, living in `androidMain` alongside the rest of the
 * feature. Because it (and the task entities/DAOs) never enter `commonMain`, the desktop build compiles none
 * of it: gating the feature gates its storage too.
 *
 * Uses **classic Android Room** (`Room.databaseBuilder(context, Class, name)` → generated `TaskDatabase_Impl`),
 * NOT the KMP `@ConstructedBy`/`RoomDatabaseConstructor` mechanism — that requires an `expect object` in a
 * parent source set, which is impossible for a single-target DB defined only in a leaf source set. Driver +
 * destructive fallback mirror the shared [com.sabreware.aide.data.chat.AideDatabase]; a separate `tasks.db` file.
 */
@Database(
    entities = [
        TaskEntity::class,
        TaskGroupEntity::class,
    ],
    // See AideDatabase: a rewritten 1.json is a crash on every existing install, so the schema change that
    // rewrote it ships as version 2 and the destructive fallback wipes cleanly. `schemaCheck` fails the
    // build if a committed N.json is ever edited again instead of a new one being added.
    version = 2,
    exportSchema = true,
)
abstract class TaskDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun taskGroupDao(): TaskGroupDao
}

/** Build the tasks DB against its own `tasks.db` file. Registered as a Koin single in `:app` (needs Context). */
fun buildTaskDatabase(context: Context): TaskDatabase {
    val app = context.applicationContext
    val dbPath = app.getDatabasePath("tasks.db").absolutePath
    return Room.databaseBuilder(app, TaskDatabase::class.java, dbPath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
}
