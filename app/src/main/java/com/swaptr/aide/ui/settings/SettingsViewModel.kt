package com.swaptr.aide.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swaptr.aide.data.prefs.UserPreferencesRepository
import com.swaptr.aide.domain.tools.fs.FileSystemRoots
import com.swaptr.aide.domain.tools.fs.RegisteredRoot
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class SettingsUiState(
    val filesystemToolEnabled: Boolean = false,
    val hasAllFilesAccess: Boolean = false,
    val roots: List<RegisteredRoot> = emptyList(),
    val transientMessage: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val userPrefs: UserPreferencesRepository,
    private val roots: FileSystemRoots,
) : ViewModel() {

    private val transient = MutableStateFlow<String?>(null)
    private val accessTick = MutableStateFlow(0)

    val uiState: StateFlow<SettingsUiState> = combine(
        userPrefs.filesystemToolEnabledFlow,
        roots.rootsFlow,
        transient,
        accessTick,
    ) { fsEnabled, rootList, msg, _ ->
        SettingsUiState(
            filesystemToolEnabled = fsEnabled,
            hasAllFilesAccess = Environment.isExternalStorageManager(),
            roots = rootList,
            transientMessage = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setFilesystemToolEnabled(enabled: Boolean) {
        viewModelScope.launch { userPrefs.setFilesystemToolEnabled(enabled) }
    }

    // Re-poll permission state on return from system settings (avoids needing a process restart).
    fun refreshPermissionState() {
        accessTick.value = accessTick.value + 1
    }

    // Non-primary trees (SD/USB) unsupported in v1.
    fun onTreeUriPicked(uri: Uri, requestedKey: String? = null) {
        viewModelScope.launch {
            val tree = DocumentFile.fromTreeUri(appContext, uri)
            val suggestedName = tree?.name?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment.orEmpty().substringAfterLast(':').ifBlank { "Folder" }
            val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            val absolutePath = docId?.let { absolutePathFor(it) }
            if (absolutePath == null) {
                transient.value = "That folder isn't on primary storage — pick one under Internal storage."
                return@launch
            }
            val dir = File(absolutePath)
            if (!dir.exists() || !dir.isDirectory) {
                transient.value = "Folder is not accessible at $absolutePath"
                return@launch
            }
            val key = requestedKey?.takeIf { it.isNotBlank() } ?: dedupeKey(suggestedName)
            roots.register(
                RegisteredRoot(
                    key = key,
                    displayName = suggestedName,
                    absolutePath = absolutePath,
                ),
            )
            transient.value = "Added '$key'"
        }
    }

    fun removeRoot(key: String) {
        viewModelScope.launch {
            roots.unregister(key)
            transient.value = "Removed '$key'"
        }
    }

    fun acknowledgeTransient() {
        transient.value = null
    }

    private fun dedupeKey(base: String): String {
        val existing = roots.snapshot().map { it.key }.toSet()
        if (base !in existing) return base
        var i = 2
        while ("${base}_$i" in existing) i++
        return "${base}_$i"
    }

    private fun absolutePathFor(docId: String): String? {
        // "primary:Foo/Bar" → /storage/emulated/0/Foo/Bar; non-primary volumes unreliable for File access.
        if (!docId.startsWith("primary:")) return null
        val rest = docId.removePrefix("primary:")
        val base = Environment.getExternalStorageDirectory().absolutePath
        return if (rest.isEmpty()) base else "$base/$rest"
    }

    fun allFilesAccessIntent(): Intent =
        Intent(
            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            "package:${appContext.packageName}".toUri(),
        )
}
