package com.swaptr.aide.data.task

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskGroupDao {

    @Query(
        """
        SELECT * FROM task_groups
        WHERE isHidden = 0
        ORDER BY sortOrder ASC, name ASC
        """,
    )
    fun observeVisible(): Flow<List<TaskGroupEntity>>

    @Query("SELECT * FROM task_groups ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<TaskGroupEntity>>

    @Query("SELECT * FROM task_groups WHERE id = :id")
    suspend fun getById(id: String): TaskGroupEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(group: TaskGroupEntity): Long

    // @Upsert (not REPLACE) keeps row id stable; REPLACE would trip RESTRICT FK on re-seed.
    @Upsert
    suspend fun upsert(group: TaskGroupEntity)

    @Query("DELETE FROM task_groups WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE task_groups SET lastUsedAt = :ts WHERE id = :id")
    suspend fun touchLastUsed(id: String, ts: Long)

    @Query("SELECT COUNT(*) FROM tasks WHERE groupId = :groupId")
    suspend fun countTasksIn(groupId: String): Int
}
