package com.sabreware.aide.data.connector.registry

import com.sabreware.aide.core.domain.connector.ConnectorCategory

/**
 * The MCP Registry has no category field, so registry connectors are bucketed by keyword over their
 * name + description. Coarse on purpose — curated connectors carry their real category and win the merge.
 */
object RegistryCategoryHeuristic {

    private val buckets = listOf(
        ConnectorCategory.CODE to listOf("code", "git", "github", "gitlab", "deploy", "ci", "build", "debug", "sentry", "error", "lint", "compiler", "devtool"),
        ConnectorCategory.COMMUNICATION to listOf("slack", "email", "mail", "message", "chat", "sms", "discord", "intercom", "support", "inbox"),
        ConnectorCategory.DESIGN to listOf("design", "figma", "canva", "image", "photo", "diagram", "ui", "webflow", "logo"),
        ConnectorCategory.DATA to listOf("database", "sql", "postgres", "bigquery", "analytics", "payment", "stripe", "invoice", "metric", "warehouse", "query"),
        ConnectorCategory.KNOWLEDGE to listOf("docs", "documentation", "wiki", "search", "knowledge", "notion", "confluence", "research", "model", "dataset"),
        ConnectorCategory.PRODUCTIVITY to listOf("task", "project", "issue", "calendar", "todo", "jira", "asana", "linear", "workspace", "crm", "schedule"),
    )

    fun categorize(name: String, description: String): ConnectorCategory {
        val hay = (name + " " + description).lowercase()
        return buckets.firstOrNull { (_, words) -> words.any { it in hay } }?.first ?: ConnectorCategory.OTHER
    }
}
