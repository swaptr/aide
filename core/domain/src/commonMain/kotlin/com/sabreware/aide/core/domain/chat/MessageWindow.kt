package com.sabreware.aide.core.domain.chat

/**
 * One slice of a chat's messages, oldest first: what a long conversation keeps in memory instead of all of it.
 * [hasOlder] says whether rows exist before [messages] — the list loads them when it scrolls to its top.
 */
data class MessageWindow(
    val messages: List<StoredMessage>,
    val hasOlder: Boolean,
)
