package com.sabreware.aide.feature.tasks.domain

// id: `builtin.<slug>` for seeds (stable across reinstall), UUID for user groups.
// Deleting a non-empty user group is rejected at the repository so tasks are never orphaned.
data class TaskGroup(
    val id: String,
    val name: String,
    val description: String?,
    val iconName: String?,
    val isBuiltIn: Boolean,
    val sortOrder: Int,
    val isHidden: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long?,
)
