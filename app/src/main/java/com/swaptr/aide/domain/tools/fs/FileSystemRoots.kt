package com.swaptr.aide.domain.tools.fs

import android.content.Context
import android.util.Log
import com.swaptr.aide.data.prefs.UserPreferencesRepository
import com.swaptr.aide.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FileSystem"

@Singleton
class FileSystemRoots @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPrefs: UserPreferencesRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val directBackend = DirectFileSystemBackend()

    val rootsFlow: StateFlow<List<RegisteredRoot>> = userPrefs.registeredFsRootsJsonFlow
        .map { decode(it) }
        .stateIn(appScope, SharingStarted.Eagerly, emptyList())

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
        userPrefs.setRegisteredFsRootsJson(encoded)
    }

    private fun decode(jsonText: String): List<RegisteredRoot> = runCatching {
        json.decodeFromString(ListSerializer(RegisteredRoot.serializer()), jsonText)
    }.onFailure { Log.w(TAG, "failed to decode fs roots json", it) }.getOrDefault(emptyList())
}
