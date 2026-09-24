package com.sabreware.aide.core.domain.connector

/** Connector name → Simple Icons slug, for the cases where the auto-derived slug wouldn't match. */
object ConnectorIconSlugs {

    private val overrides = mapOf(
        "google drive" to "googledrive",
        "google calendar" to "googlecalendar",
        "microsoft 365" to "microsoftoffice",
        "hugging face" to "huggingface",
        "cloudflare docs" to "cloudflare",
    )

    fun override(name: String): String? = overrides[name.lowercase().trim()]
}
