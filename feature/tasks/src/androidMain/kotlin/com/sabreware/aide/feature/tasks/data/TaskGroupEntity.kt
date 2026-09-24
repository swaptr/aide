package com.sabreware.aide.feature.tasks.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// id: `builtin.<slug>` for seeds, UUID for user. Deleting a non-empty user group is
// rejected at repo so we never orphan tasks.
@Entity(tableName = "task_groups")
data class TaskGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String? = null,
    val iconName: String? = null,
    val isBuiltIn: Boolean = false,
    val sortOrder: Int = 0,
    val isHidden: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long? = null,
)
