package com.swaptr.aide.domain.llm

import java.util.concurrent.ConcurrentHashMap

// One-way activation; hiding schemas mid-session would surprise the model.
// ConcurrentHashMap keyset because meta-tool handler mutates from engine dispatcher thread.
class ToolActivationState {

    private val backing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    val activated: Set<String> get() = backing.toSet()

    fun isActive(category: String): Boolean = backing.contains(category)

    fun activate(category: String): Boolean = backing.add(category)
}
