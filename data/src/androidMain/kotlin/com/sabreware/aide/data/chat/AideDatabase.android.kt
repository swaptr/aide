package com.sabreware.aide.data.chat

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.sabreware.aide.core.common.di.IO
import kotlinx.coroutines.Dispatchers

// Android-side builder: resolves the SAME on-disk file ("aide.db") the old `Room.databaseBuilder(context,
// AideDatabase::class.java, "aide.db")` used, so existing installs keep their data. The query
// CoroutineContext is Dispatchers.IO here (JVM/Android-only — can't live in the common `buildDatabase`).
// `buildDatabase` (commonMain) finishes the chain: driver + destructive fallback + build.
fun androidDatabaseBuilder(context: Context): RoomDatabase.Builder<AideDatabase> {
    val dbFile = context.applicationContext.getDatabasePath("aide.db")
    return Room.databaseBuilder<AideDatabase>(context.applicationContext, dbFile.absolutePath)
        .setQueryCoroutineContext(Dispatchers.IO)
}
