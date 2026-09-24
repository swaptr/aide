package com.sabreware.aide.data.chat

import androidx.room.Room
import androidx.room.RoomDatabase
import com.sabreware.aide.core.common.di.IO
import java.io.File
import kotlinx.coroutines.Dispatchers

/**
 * Desktop-side Room builder — mirror of [androidDatabaseBuilder]. Puts the DB under [dataDir] and pins the
 * query CoroutineContext to `Dispatchers.IO` (JVM-only, so it can't live in the common [buildDatabase]).
 * `buildDatabase` finishes the chain (bundled SQLite driver + destructive fallback + build).
 *
 * [dataDir] is a PARAMETER because where a desktop keeps app state is the host's convention, not the
 * database's business: one `jvm("desktop")` binary runs on Linux, macOS and Windows, and each puts it
 * somewhere different. This file used to define `aideAppDataDir()` — a hard-coded `~/.aide` — which the
 * secret store, the connector cache and `PlatformPaths` all imported from here. That inverted the layering
 * and was wrong on two of the three hosts.
 */
fun desktopDatabaseBuilder(dataDir: File): RoomDatabase.Builder<AideDatabase> {
    val dbFile = File(dataDir, "aide.db")
    return Room.databaseBuilder<AideDatabase>(name = dbFile.absolutePath)
        .setQueryCoroutineContext(Dispatchers.IO)
}
