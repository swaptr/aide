package com.sabreware.aide.feature.tasks.data

import com.sabreware.aide.feature.tasks.domain.Task
import com.sabreware.aide.feature.tasks.domain.TaskGroup

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

fun TaskGroupEntity.toDomain(): TaskGroup = TaskGroup(
    id = id,
    name = name,
    description = description,
    iconName = iconName,
    isBuiltIn = isBuiltIn,
    sortOrder = sortOrder,
    isHidden = isHidden,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastUsedAt = lastUsedAt,
)
