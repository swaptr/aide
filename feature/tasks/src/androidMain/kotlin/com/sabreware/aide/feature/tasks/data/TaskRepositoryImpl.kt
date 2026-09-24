package com.sabreware.aide.feature.tasks.data

import com.sabreware.aide.feature.tasks.domain.GroupChipItem
import com.sabreware.aide.feature.tasks.domain.Task
import com.sabreware.aide.feature.tasks.domain.TaskGroup
import com.sabreware.aide.feature.tasks.domain.TaskRepository
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock

// Built-ins editable in place; delete() rejects them (would resurrect on next seed).
// Reseeding is insert-if-missing only so user edits survive launches.
// Room entities stay internal; every read maps to the domain model at this boundary.
@OptIn(ExperimentalUuidApi::class)
class TaskRepositoryImpl(
    private val taskDao: TaskDao,
    private val groupDao: TaskGroupDao,
) : TaskRepository {

    override fun observeVisible(): Flow<List<Task>> =
        taskDao.observeVisible().map { it.map(TaskEntity::toDomain) }

    override fun observeAll(): Flow<List<Task>> =
        taskDao.observeAll().map { it.map(TaskEntity::toDomain) }

    override fun observe(id: String): Flow<Task?> =
        taskDao.observe(id).map { it?.toDomain() }

    override fun observeByGroup(groupId: String): Flow<List<Task>> =
        taskDao.observeByGroup(groupId).map { it.map(TaskEntity::toDomain) }

    override fun observeGroups(): Flow<List<TaskGroup>> =
        groupDao.observeVisible().map { it.map(TaskGroupEntity::toDomain) }

    override fun observeAllGroups(): Flow<List<TaskGroup>> =
        groupDao.observeAll().map { it.map(TaskGroupEntity::toDomain) }

    override suspend fun getGroup(id: String): TaskGroup? = groupDao.getById(id)?.toDomain()

    // Sorts by most-recently-used member then sortOrder then name so touched groups bubble up.
    override fun observeChipItems(): Flow<List<GroupChipItem>> =
        combine(groupDao.observeVisible(), taskDao.observeVisible()) { groups, tasks ->
            val byGroupId = tasks.groupBy { it.groupId }
            groups.mapNotNull { group ->
                val members = byGroupId[group.id].orEmpty()
                if (members.isEmpty()) return@mapNotNull null
                val ordered = members.sortedWith(
                    compareByDescending<TaskEntity> { it.lastUsedAt ?: Long.MIN_VALUE }
                        .thenBy { it.name },
                )
                val recency = members.maxOfOrNull { it.lastUsedAt ?: Long.MIN_VALUE }
                    ?.takeIf { it != Long.MIN_VALUE }
                GroupChipItem(
                    group = group.toDomain(),
                    members = ordered.map { it.toDomain() },
                    mostRecentlyUsedAt = recency,
                )
            }.sortedWith(
                compareByDescending<GroupChipItem> { it.mostRecentlyUsedAt ?: Long.MIN_VALUE }
                    .thenBy { it.group.sortOrder }
                    .thenBy { it.group.name },
            )
        }

    override suspend fun getById(id: String): Task? = taskDao.getById(id)?.toDomain()

    override suspend fun createCustom(
        name: String,
        description: String,
        promptTemplate: String,
        groupId: String,
    ): Task {
        require(name.isNotBlank()) { "Task name must not be blank" }
        require(groupId.isNotBlank()) { "A group must be selected" }
        require(promptTemplate.contains(Task.PLACEHOLDER)) {
            "Prompt template must contain ${Task.PLACEHOLDER}"
        }
        groupDao.getById(groupId) ?: error("Group not found: $groupId")
        val now = Clock.System.now().toEpochMilliseconds()
        val entity = TaskEntity(
            id = Uuid.random().toString(),
            name = name.trim(),
            description = description.trim(),
            promptTemplate = promptTemplate,
            groupId = groupId,
            isBuiltIn = false,
            isHidden = false,
            createdAt = now,
            updatedAt = now,
            lastUsedAt = null,
        )
        taskDao.upsert(entity)
        return entity.toDomain()
    }

    override suspend fun clone(sourceId: String, newName: String?): Task {
        val src = taskDao.getById(sourceId)
            ?: error("Task not found: $sourceId")
        return createCustom(
            name = newName ?: "${src.name} (copy)",
            description = src.description,
            promptTemplate = src.promptTemplate,
            groupId = src.groupId,
        )
    }

    override suspend fun updateTask(
        id: String,
        name: String,
        description: String,
        promptTemplate: String,
        groupId: String,
    ): Task {
        val existing = taskDao.getById(id) ?: error("Task not found: $id")
        require(name.isNotBlank()) { "Task name must not be blank" }
        require(groupId.isNotBlank()) { "A group must be selected" }
        require(promptTemplate.contains(Task.PLACEHOLDER)) {
            "Prompt template must contain ${Task.PLACEHOLDER}"
        }
        groupDao.getById(groupId) ?: error("Group not found: $groupId")
        val updated = existing.copy(
            name = name.trim(),
            description = description.trim(),
            promptTemplate = promptTemplate,
            groupId = groupId,
            updatedAt = Clock.System.now().toEpochMilliseconds(),
        )
        taskDao.upsert(updated)
        return updated.toDomain()
    }

    override suspend fun delete(id: String) {
        val existing = taskDao.getById(id) ?: return
        require(!existing.isBuiltIn) { "Built-in tasks cannot be deleted. Hide them instead." }
        taskDao.deleteById(id)
    }

    override suspend fun setHidden(id: String, hidden: Boolean) {
        taskDao.setHidden(id, hidden, Clock.System.now().toEpochMilliseconds())
    }

    override suspend fun markUsed(id: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        taskDao.touchLastUsed(id, now)
        // Bump parent group recency so its chip moves up without a separate task invoke.
        taskDao.getById(id)?.groupId?.let { gid ->
            groupDao.touchLastUsed(gid, now)
        }
    }

    override suspend fun createGroup(name: String, description: String?): TaskGroup {
        require(name.isNotBlank()) { "Group name must not be blank" }
        val now = Clock.System.now().toEpochMilliseconds()
        val group = TaskGroupEntity(
            id = Uuid.random().toString(),
            name = name.trim(),
            description = description?.trim()?.takeIf { it.isNotBlank() },
            isBuiltIn = false,
            // Push user groups after built-ins by default; specific reorder
            // UI is post-MVP.
            sortOrder = 1_000,
            isHidden = false,
            createdAt = now,
            updatedAt = now,
            lastUsedAt = null,
        )
        groupDao.upsert(group)
        return group.toDomain()
    }

    override suspend fun renameGroup(id: String, name: String, description: String?): TaskGroup {
        val existing = groupDao.getById(id) ?: error("Group not found: $id")
        require(!existing.isBuiltIn) { "Built-in groups cannot be renamed." }
        require(name.isNotBlank()) { "Group name must not be blank" }
        val updated = existing.copy(
            name = name.trim(),
            description = description?.trim()?.takeIf { it.isNotBlank() },
            updatedAt = Clock.System.now().toEpochMilliseconds(),
        )
        groupDao.upsert(updated)
        return updated.toDomain()
    }

    // Rejects built-ins and non-empty groups (prevents orphaned tasks).
    override suspend fun deleteGroup(id: String) {
        val existing = groupDao.getById(id) ?: return
        require(!existing.isBuiltIn) { "Built-in groups cannot be deleted." }
        val members = groupDao.countTasksIn(id)
        require(members == 0) {
            "Group still has $members task(s). Move or delete them first."
        }
        groupDao.deleteById(id)
    }

    // Built-in tasks insert-if-missing only (user edits authoritative); groups re-upsert
    // (not user-editable yet). Groups must seed first to satisfy task FK.
    override suspend fun seedBuiltInsIfMissing() {
        seedBuiltInGroupsIfMissing()
        seedBuiltInTasksIfMissing()
    }

    private suspend fun seedBuiltInGroupsIfMissing() {
        val now = Clock.System.now().toEpochMilliseconds()
        BuiltInGroups.seeds.forEach { seed ->
            val existing = groupDao.getById(seed.id)
            if (existing == null) {
                groupDao.insertIfMissing(seed.copy(createdAt = now, updatedAt = now))
            } else if (existing.isBuiltIn) {
                groupDao.upsert(
                    seed.copy(
                        createdAt = existing.createdAt,
                        updatedAt = now,
                        lastUsedAt = existing.lastUsedAt,
                        isHidden = existing.isHidden,
                    ),
                )
            }
        }
    }

    private suspend fun seedBuiltInTasksIfMissing() {
        val now = Clock.System.now().toEpochMilliseconds()
        BuiltInTasks.seeds.forEach { seed ->
            if (taskDao.getById(seed.id) == null) {
                taskDao.insertIfMissing(seed.copy(createdAt = now, updatedAt = now))
            }
        }
    }
}
