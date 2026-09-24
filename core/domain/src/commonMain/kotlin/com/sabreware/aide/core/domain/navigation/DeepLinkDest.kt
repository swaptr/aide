package com.sabreware.aide.core.domain.navigation

/**
 * Deep-link destination keys carried in the launch intent's `navigate_to` extra. Single source of truth
 * in commonMain so the shared [com.sabreware.aide.ui.app.AppShell] deep-link router and the Android entry
 * points (MainActivity / IME / assistant) agree on the string values.
 */
object DeepLinkDest {
    const val EXTRA_NAVIGATE_TO = "navigate_to"
    const val DEST_TASK_EDIT_NEW = "task_edit_new"
    const val DEST_TASKS = "tasks"
    const val DEST_CUSTOM_INSTRUCTION = "custom_instruction"
    const val DEST_MODELS = "models"
}
