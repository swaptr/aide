package com.sabreware.aide.core.domain.tools.fs

import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.common.prefs.peek
import com.sabreware.aide.core.domain.tools.ToolPrefs
import com.sabreware.aide.core.domain.util.AideLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private const val TAG = "FileSystem"

class FileSystemRoots(
    private val userPrefs: PreferenceStore,
    // Wired explicitly in di/KoinModules (get(APPLICATION_SCOPE)); no qualifier annotation needed here.
    private val appScope: CoroutineScope,
    private val directBackend: FileSystemBackend,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Seeded from the prefs snapshot: an empty seed made an early tool call report "no folders granted".
    val rootsFlow: StateFlow<List<RegisteredRoot>> = userPrefs.flow(ToolPrefs.FsRootsJson)
        .map { decode(it) }
        .stateIn(appScope, SharingStarted.Eagerly, decode(userPrefs.peek(ToolPrefs.FsRootsJson)))

    fun snapshot(): List<RegisteredRoot> = rootsFlow.value

    fun find(key: String): RegisteredRoot? = snapshot().firstOrNull { it.key == key }

    @Suppress("UNUSED_PARAMETER")
    fun backendFor(root: RegisteredRoot): FileSystemBackend = directBackend

    fun availableKeysSummary(): String {
        val list = snapshot()
        if (list.isEmpty()) return "(none granted yet — ask the user to open Settings to add a folder)"
        return list.joinToString(", ") { "${it.key} (${it.absolutePath})" }
    }

    fun register(root: RegisteredRoot) {
        appScope.launch {
            val current = snapshot().toMutableList()
            current.removeAll { it.key == root.key }
            current += root
            persist(current)
        }
    }

    fun unregister(key: String) {
        appScope.launch {
            val next = snapshot().filter { it.key != key }
            persist(next)
        }
    }

    private suspend fun persist(list: List<RegisteredRoot>) {
        val encoded = json.encodeToString(ListSerializer(RegisteredRoot.serializer()), list)
        userPrefs.set(ToolPrefs.FsRootsJson, encoded)
    }

    private fun decode(jsonText: String): List<RegisteredRoot> = runCatching {
        json.decodeFromString(ListSerializer(RegisteredRoot.serializer()), jsonText)
    }.onFailure { AideLog.w(TAG, "failed to decode fs roots json", it) }.getOrDefault(emptyList())
}
