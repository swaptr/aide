package com.swaptr.aide.domain.tools.contacts

import com.swaptr.aide.domain.llm.ToolEnvelope
import com.swaptr.aide.domain.llm.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

sealed class ContactsResult : ToolResult {

    data class Phone(val number: String, val type: String, val isPrimary: Boolean)
    data class Email(val address: String, val type: String)

    data class Match(
        val contactId: Long,
        val lookupKey: String,
        val displayName: String,
        val phones: List<Phone>,
        val emails: List<Email>,
        val thumbnailUri: String?,
    )

    data class Resolved(val matches: List<Match>, val ambiguous: Boolean) : ContactsResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.success {
            put("matches", buildJsonArray {
                matches.forEach { m -> add(m.toJsonObject()) }
            })
            put("ambiguous", JsonPrimitive(ambiguous))
        }
    }

    data class Deleted(val contactId: Long, val deleted: Boolean) : ContactsResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.success {
            put("contact_id", JsonPrimitive(contactId))
            put("deleted", JsonPrimitive(deleted))
        }
    }

    data class Err(val code: String, val message: String) : ContactsResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.failure(code, message)
    }
}

private fun ContactsResult.Match.toJsonObject(): JsonObject = buildJsonObject {
    put("contact_id", JsonPrimitive(contactId))
    put("lookup_key", JsonPrimitive(lookupKey))
    put("display_name", JsonPrimitive(displayName))
    put("phones", buildJsonArray {
        phones.forEach { p ->
            add(buildJsonObject {
                put("number", JsonPrimitive(p.number))
                put("type", JsonPrimitive(p.type))
                put("is_primary", JsonPrimitive(p.isPrimary))
            })
        }
    })
    put("emails", buildJsonArray {
        emails.forEach { e ->
            add(buildJsonObject {
                put("address", JsonPrimitive(e.address))
                put("type", JsonPrimitive(e.type))
            })
        }
    })
    if (thumbnailUri != null) put("thumbnail_uri", JsonPrimitive(thumbnailUri))
}
