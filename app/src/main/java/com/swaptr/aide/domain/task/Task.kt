package com.swaptr.aide.domain.task

import com.swaptr.aide.data.task.TaskEntity
import com.swaptr.aide.data.task.TaskRepository

data class Task(
    val id: String,
    val name: String,
    val description: String,
    val promptTemplate: String,
    val groupId: String,
    val isBuiltIn: Boolean,
    val isHidden: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long?,
) {
    fun buildPrompt(fieldText: String): String =
        promptTemplate.replace(TaskRepository.PLACEHOLDER, fieldText)
}

fun TaskEntity.toDomain(): Task = Task(
    id = id,
    name = name,
    description = description,
    promptTemplate = promptTemplate,
    groupId = groupId,
    isBuiltIn = isBuiltIn,
    isHidden = isHidden,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastUsedAt = lastUsedAt,
)
