package com.sabreware.aide.feature.tasks.domain

import kotlinx.coroutines.flow.Flow

/**
 * Domain-facing contract for task/group storage. The implementation
 * ([com.sabreware.aide.feature.tasks.data.TaskRepositoryImpl]) is Room-backed and maps entities to these domain
 * models at the boundary so presentation/use-cases never touch persistence types.
 *
 * Built-ins are editable in place; [delete]/[deleteGroup]/[renameGroup] reject them. Reseeding is
 * insert-if-missing only, so user edits survive launches.
 */
interface TaskRepository {

    fun observeVisible(): Flow<List<Task>>
    fun observeAll(): Flow<List<Task>>
    fun observe(id: String): Flow<Task?>
    fun observeByGroup(groupId: String): Flow<List<Task>>
    fun observeGroups(): Flow<List<TaskGroup>>
    fun observeAllGroups(): Flow<List<TaskGroup>>
    fun observeChipItems(): Flow<List<GroupChipItem>>

    suspend fun getGroup(id: String): TaskGroup?
    suspend fun getById(id: String): Task?

    suspend fun createCustom(
        name: String,
        description: String,
        promptTemplate: String,
        groupId: String,
    ): Task

    suspend fun clone(sourceId: String, newName: String? = null): Task

    suspend fun updateTask(
        id: String,
        name: String,
        description: String,
        promptTemplate: String,
        groupId: String,
    ): Task

    suspend fun delete(id: String)
    suspend fun setHidden(id: String, hidden: Boolean)
    suspend fun markUsed(id: String)

    suspend fun createGroup(name: String, description: String? = null): TaskGroup
    suspend fun renameGroup(id: String, name: String, description: String? = null): TaskGroup
    suspend fun deleteGroup(id: String)

    suspend fun seedBuiltInsIfMissing()
}
