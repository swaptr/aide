package com.swaptr.aide.data.chat

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.swaptr.aide.data.task.TaskDao
import com.swaptr.aide.data.task.TaskEntity
import com.swaptr.aide.data.task.TaskGroupDao
import com.swaptr.aide.data.task.TaskGroupEntity

@Database(
    entities = [
        ChatEntity::class,
        MessageEntity::class,
        TaskEntity::class,
        TaskGroupEntity::class,
    ],
    version = 10,
    exportSchema = false,
)
abstract class AideDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun taskDao(): TaskDao
    abstract fun taskGroupDao(): TaskGroupDao

    companion object {
        fun build(context: Context): AideDatabase = Room.databaseBuilder(
            context.applicationContext,
            AideDatabase::class.java,
            "aide.db",
        )
            // Early-dev: drop DB on schema change (Room 2.7 requires explicit allow-flag).
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }
}
