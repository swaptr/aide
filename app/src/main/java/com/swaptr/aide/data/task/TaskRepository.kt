package com.swaptr.aide.data.task

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID

// Built-ins editable in place; delete() rejects them (would resurrect on next seed).
// Reseeding is insert-if-missing only so user edits survive launches.
class TaskRepository(
    private val taskDao: TaskDao,
    private val groupDao: TaskGroupDao,
) {

    fun observeVisible(): Flow<List<TaskEntity>> = taskDao.observeVisible()

    fun observeAll(): Flow<List<TaskEntity>> = taskDao.observeAll()

    fun observe(id: String): Flow<TaskEntity?> = taskDao.observe(id)

    fun observeByGroup(groupId: String): Flow<List<TaskEntity>> =
        taskDao.observeByGroup(groupId)

    fun observeGroups(): Flow<List<TaskGroupEntity>> = groupDao.observeVisible()

    fun observeAllGroups(): Flow<List<TaskGroupEntity>> = groupDao.observeAll()

    suspend fun getGroup(id: String): TaskGroupEntity? = groupDao.getById(id)

    // Sorts by most-recently-used member then sortOrder then name so touched groups bubble up.
    fun observeChipItems(): Flow<List<GroupChipItem>> =
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
                    group = group,
                    members = ordered,
                    mostRecentlyUsedAt = recency,
                )
            }.sortedWith(
                compareByDescending<GroupChipItem> { it.mostRecentlyUsedAt ?: Long.MIN_VALUE }
                    .thenBy { it.group.sortOrder }
                    .thenBy { it.group.name },
            )
        }

    suspend fun getById(id: String): TaskEntity? = taskDao.getById(id)

    suspend fun createCustom(
        name: String,
        description: String,
        promptTemplate: String,
        groupId: String,
    ): TaskEntity {
        require(name.isNotBlank()) { "Task name must not be blank" }
        require(groupId.isNotBlank()) { "A group must be selected" }
        require(promptTemplate.contains(PLACEHOLDER)) {
            "Prompt template must contain $PLACEHOLDER"
        }
        groupDao.getById(groupId) ?: error("Group not found: $groupId")
        val now = System.currentTimeMillis()
        val entity = TaskEntity(
            id = UUID.randomUUID().toString(),
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
        return entity
    }

    suspend fun clone(sourceId: String, newName: String? = null): TaskEntity {
        val src = taskDao.getById(sourceId)
            ?: error("Task not found: $sourceId")
        return createCustom(
            name = newName ?: "${src.name} (copy)",
            description = src.description,
            promptTemplate = src.promptTemplate,
            groupId = src.groupId,
        )
    }

    suspend fun updateTask(
        id: String,
        name: String,
        description: String,
        promptTemplate: String,
        groupId: String,
    ): TaskEntity {
        val existing = taskDao.getById(id) ?: error("Task not found: $id")
        require(name.isNotBlank()) { "Task name must not be blank" }
        require(groupId.isNotBlank()) { "A group must be selected" }
        require(promptTemplate.contains(PLACEHOLDER)) {
            "Prompt template must contain $PLACEHOLDER"
        }
        groupDao.getById(groupId) ?: error("Group not found: $groupId")
        val updated = existing.copy(
            name = name.trim(),
            description = description.trim(),
            promptTemplate = promptTemplate,
            groupId = groupId,
            updatedAt = System.currentTimeMillis(),
        )
        taskDao.upsert(updated)
        return updated
    }

    suspend fun delete(id: String) {
        val existing = taskDao.getById(id) ?: return
        require(!existing.isBuiltIn) { "Built-in tasks cannot be deleted. Hide them instead." }
        taskDao.deleteById(id)
    }

    suspend fun setHidden(id: String, hidden: Boolean) {
        taskDao.setHidden(id, hidden, System.currentTimeMillis())
    }

    suspend fun markUsed(id: String) {
        val now = System.currentTimeMillis()
        taskDao.touchLastUsed(id, now)
        // Bump parent group recency so its chip moves up without a separate task invoke.
        taskDao.getById(id)?.groupId?.let { gid ->
            groupDao.touchLastUsed(gid, now)
        }
    }

    suspend fun createGroup(name: String, description: String? = null): TaskGroupEntity {
        require(name.isNotBlank()) { "Group name must not be blank" }
        val now = System.currentTimeMillis()
        val group = TaskGroupEntity(
            id = UUID.randomUUID().toString(),
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
        return group
    }

    suspend fun renameGroup(id: String, name: String, description: String? = null): TaskGroupEntity {
        val existing = groupDao.getById(id) ?: error("Group not found: $id")
        require(!existing.isBuiltIn) { "Built-in groups cannot be renamed." }
        require(name.isNotBlank()) { "Group name must not be blank" }
        val updated = existing.copy(
            name = name.trim(),
            description = description?.trim()?.takeIf { it.isNotBlank() },
            updatedAt = System.currentTimeMillis(),
        )
        groupDao.upsert(updated)
        return updated
    }

    // Rejects built-ins and non-empty groups (prevents orphaned tasks).
    suspend fun deleteGroup(id: String) {
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
    suspend fun seedBuiltInsIfMissing() {
        seedBuiltInGroupsIfMissing()
        seedBuiltInTasksIfMissing()
    }

    private suspend fun seedBuiltInGroupsIfMissing() {
        val now = System.currentTimeMillis()
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
        val now = System.currentTimeMillis()
        BuiltInTasks.seeds.forEach { seed ->
            if (taskDao.getById(seed.id) == null) {
                taskDao.insertIfMissing(seed.copy(createdAt = now, updatedAt = now))
            }
        }
    }

    companion object {
        const val PLACEHOLDER = "{text}"
    }
}

// One group → one chip; multi-member chips show `▾` and open the side panel on tap.
data class GroupChipItem(
    val group: TaskGroupEntity,
    val members: List<TaskEntity>,
    val mostRecentlyUsedAt: Long?,
)
