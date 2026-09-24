package com.sabreware.aide.data.chat

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

@Database(
    entities = [
        ChatEntity::class,
        MessageEntity::class,
    ],
    // Bumped to 2: 1.json was rewritten in place across four commits, so every install created against an
    // older 1 is a `Room cannot verify the data integrity` crash at open. Destructive fallback fires on a
    // missing migration PATH, so a NEW version number is what makes it wipe and recreate instead of throw.
    // Any future entity change bumps this and lets KSP write the next N.json — `schemaCheck` enforces it.
    version = 2,
    exportSchema = true,
)
@ConstructedBy(AideDatabaseConstructor::class)
abstract class AideDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
}

// KMP Room: the `actual` object is synthesized by Room's KSP codegen per target, so there is no
// hand-written actual — hence the suppression (official androidx.room KMP recipe).
@Suppress("KotlinNoActualForExpect")
expect object AideDatabaseConstructor : RoomDatabaseConstructor<AideDatabase> {
    override fun initialize(): AideDatabase
}

// Common builder finish: bundled SQLite driver + destructive fallback (the early-dev backstop that
// recreates the DB when no migration path exists). The platform supplies the target-specific
// `RoomDatabase.Builder` (file path) and its query CoroutineContext — `Dispatchers.IO` is JVM/Native-only
// so it can't live here. Schemas are exported to data/schemas (VCS), one file per version, written once:
// see `schemaCheck` in the root build for why rewriting one instead of adding the next is a crash.
fun buildDatabase(builder: RoomDatabase.Builder<AideDatabase>): AideDatabase =
    builder
        .setDriver(BundledSQLiteDriver())
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
