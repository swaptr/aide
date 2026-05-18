package com.swaptr.aide.domain.llm.dispatch

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RateLimiter @Inject constructor() {

    enum class Category { READ, INTENT_FIRE, NETWORK, DESTRUCTIVE }

    sealed class Outcome {
        data object Allowed : Outcome()
        data class Denied(val retryInSeconds: Int) : Outcome()
    }

    private val categoryFor: Map<String, Category> = buildMap {
        listOf(
            "CurrentTime", "Calculator", "WebFetch",
            "ListFiles", "FindFiles", "FileInfo", "ReadFile",
            "ResolveContact",
            "CalendarListCalendars", "CalendarGetEvents", "CalendarFindEvents",
            "ClipboardGet",
        ).forEach { put(it, Category.READ) }
        listOf(
            "SetAlarm", "SetTimer",
            "ShowAlarms", "ShowTimers", "DismissAlarm", "SnoozeAlarm", "DismissTimer",
            "Dial", "SendSms", "ComposeEmail",
            "AddContact", "EditOrAddContact", "ShowOrCreateContact",
            "ViewContacts", "OpenCallLog", "PickContact",
            "CalendarAddEvent", "CalendarEditEvent",
        ).forEach { put(it, Category.INTENT_FIRE) }
        listOf("WebSearch").forEach { put(it, Category.NETWORK) }
        listOf(
            "MakeDir", "MoveFile", "CopyFile", "DeleteFile",
            "CalendarDeleteEvent", "DeleteContact",
            "ClipboardSet",
        ).forEach { put(it, Category.DESTRUCTIVE) }
    }

    private val perTurn: MutableMap<String, MutableMap<String, Int>> = mutableMapOf()

    @Synchronized
    fun resetTurn(turnId: String) {
        perTurn[turnId] = mutableMapOf()
    }

    fun defaultCapFor(toolName: String): Int = when (categoryFor[toolName] ?: Category.READ) {
        Category.READ -> 20
        Category.INTENT_FIRE -> 3
        Category.NETWORK -> 5
        Category.DESTRUCTIVE -> 2
    }

    @Synchronized
    fun acquire(toolName: String, turnId: String, cap: Int): Outcome {
        val counters = perTurn.getOrPut(turnId) { mutableMapOf() }
        val current = counters[toolName] ?: 0
        if (current >= cap) {
            // Per-turn limiter: surface 0 so the model doesn't busy-wait on a meaningless hint.
            return Outcome.Denied(retryInSeconds = 0)
        }
        counters[toolName] = current + 1
        return Outcome.Allowed
    }

    @Synchronized
    fun forget(turnId: String) {
        perTurn.remove(turnId)
    }
}
