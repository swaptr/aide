package com.sabreware.aide.core.domain.llm

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

// One-way activation; hiding schemas mid-session would surprise the model.
// Lock-guarded set because meta-tool handler mutates from engine dispatcher thread.
class ToolActivationState {

    private val lock = SynchronizedObject()
    private val backing: MutableSet<String> = mutableSetOf()

    val activated: Set<String> get() = synchronized(lock) { backing.toSet() }

    fun isActive(category: String): Boolean = synchronized(lock) { backing.contains(category) }

    fun activate(category: String): Boolean = synchronized(lock) { backing.add(category) }
}
