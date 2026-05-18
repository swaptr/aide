package com.swaptr.aide.data.task

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// id semantics: `builtin.<slug>` for seeds (stable across reinstall), UUID for user tasks.
// promptTemplate must contain literal `{text}` — validated at repo/editor, not DB.
@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = TaskGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("groupId")],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val promptTemplate: String,
    val groupId: String,
    val isBuiltIn: Boolean,
    val isHidden: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long?,
)
