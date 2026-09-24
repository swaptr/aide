package com.sabreware.aide.feature.tasks.domain

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
        promptTemplate.replace(PLACEHOLDER, fieldText)

    companion object {
        /** Template token substituted with the field's text when a task runs. */
        const val PLACEHOLDER = "{text}"
    }
}
