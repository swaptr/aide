package com.sabreware.aide.feature.tasks.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    // SQLite DESC treats NULLs as smallest, so never-used rows naturally sort last.
    @Query(
        """
        SELECT * FROM tasks
        WHERE isHidden = 0
        ORDER BY lastUsedAt DESC, name ASC
        """,
    )
    fun observeVisible(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks ORDER BY groupId ASC, name ASC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query(
        """
        SELECT * FROM tasks
        WHERE groupId = :groupId AND isHidden = 0
        ORDER BY lastUsedAt DESC, name ASC
        """,
    )
    fun observeByGroup(groupId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observe(id: String): Flow<TaskEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(task: TaskEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE tasks SET isHidden = :hidden, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setHidden(id: String, hidden: Boolean, updatedAt: Long)

    @Query("UPDATE tasks SET lastUsedAt = :ts WHERE id = :id")
    suspend fun touchLastUsed(id: String, ts: Long)

    @Query("SELECT COUNT(*) FROM tasks")
    suspend fun count(): Int
}
