package com.sabreware.aide.ui.settings.tools

import com.sabreware.aide.core.domain.tools.fs.GrantedFolderResolver
import com.sabreware.aide.core.designsystem.state.stateInUi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sabreware.aide.core.common.prefs.PrefSnapshot
import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.common.prefs.select
import com.sabreware.aide.core.domain.permission.AppPermission
import com.sabreware.aide.core.domain.permission.CategoryRequirement
import com.sabreware.aide.core.domain.permission.RuntimePermissionGate
import com.sabreware.aide.core.domain.permission.SpecialPermission
import com.sabreware.aide.core.domain.tools.ToolCategory
import com.sabreware.aide.core.domain.tools.Toolset
import com.sabreware.aide.core.domain.tools.ToolsetRegistry
import com.sabreware.aide.core.domain.tools.ToolPrefs
import com.sabreware.aide.core.domain.tools.fs.FileSystemRoots
import com.sabreware.aide.core.domain.tools.fs.RegisteredRoot
import com.sabreware.aide.core.domain.tools.setToolCategoryEnabled
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Switch UI is enabled && granted so a revoked perm flips row OFF without a stale pref masking.
data class ToolsSettingsUiState(
    /** The installed toolsets, in registry order — the rows the screen draws. */
    val toolsets: List<Toolset> = emptyList(),
    val enabledByCategory: Map<ToolCategory, Boolean> = emptyMap(),
    val grantedByCategory: Map<ToolCategory, Boolean> = emptyMap(),
    val askBeforeEachTool: Boolean = false,
    // Tool names the user marked "always allow" (so the confirm gate stops asking) — shown for reset.
    val alwaysAllowedTools: Set<String> = emptySet(),
    // Tool names the user marked "never allow" — blocked outright; shown for reset.
    val deniedTools: Set<String> = emptySet(),
    // Filesystem (special permission) extras: the folders the model may touch once granted.
    val roots: List<RegisteredRoot> = emptyList(),
    val transientMessage: String? = null,
)

sealed class CategoryPermissionEvent {
    /** Surfaced to the screen for snackbar feedback after the user denies a permission. */
    data class Denied(val cat: ToolCategory, val message: String) : CategoryPermissionEvent()
}

// Persist intent first; screen calls revert paths on user back-out so we don't lie about ON.
class ToolsSettingsViewModel(
    private val folderResolver: GrantedFolderResolver,
    private val prefs: PreferenceStore,
    private val gate: RuntimePermissionGate,
    private val fsRoots: FileSystemRoots,
    // The rows come from the installed toolsets, so a target shows exactly the categories it can serve
    // and a new toolset appears in Settings with no edit here.
    private val toolsets: ToolsetRegistry,
) : ViewModel() {

    private fun requirementOf(cat: ToolCategory): CategoryRequirement =
        toolsets[cat]?.requirement ?: CategoryRequirement.None

    // Seeded with the live probe (synchronous), not "nothing granted": an enabled gated tool must not draw
    // OFF with "(needs permission)" until the first resume re-polls.
    private val grantedState = MutableStateFlow(probeGrants())

    private val transient = MutableStateFlow<String?>(null)

    private val _events = MutableSharedFlow<CategoryPermissionEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    // The rows come from the in-memory registry and every toggle from ONE prefs snapshot, so the first frame
    // (seeded from `prefs.current`) and every update are built by the same function.
    val uiState: StateFlow<ToolsSettingsUiState> =
        combine(prefs.select(::toggles), grantedState, fsRoots.rootsFlow, transient, ::state)
            .stateInUi(viewModelScope, state(toggles(prefs.current), grantedState.value, fsRoots.rootsFlow.value, null)) {
                state(toggles(PrefSnapshot.Defaults), grantedState.value, fsRoots.rootsFlow.value, null)
            }

    private data class Toggles(
        val enabled: Set<String>,
        val ask: Boolean,
        val alwaysAllowed: Set<String>,
        val denied: Set<String>,
    )

    private fun toggles(p: PrefSnapshot) = Toggles(
        enabled = p[ToolPrefs.EnabledCategories],
        ask = p[ToolPrefs.AskBeforeEachTool],
        alwaysAllowed = p[ToolPrefs.AlwaysAllowed],
        denied = p[ToolPrefs.Denied],
    )

    private fun state(
        t: Toggles,
        granted: Map<ToolCategory, Boolean>,
        roots: List<RegisteredRoot>,
        message: String?,
    ) = ToolsSettingsUiState(
        toolsets = toolsets.toolsets,
        enabledByCategory = toolsets.categories.associateWith { it.id in t.enabled },
        grantedByCategory = granted,
        askBeforeEachTool = t.ask,
        alwaysAllowedTools = t.alwaysAllowed,
        deniedTools = t.denied,
        roots = roots,
        transientMessage = message,
    )

    private fun probeGrants(): Map<ToolCategory, Boolean> = toolsets.categories.associateWith { cat ->
        when (val req = requirementOf(cat)) {
            CategoryRequirement.None -> true
            is CategoryRequirement.Runtime -> gate.isGranted(req.permission)
            is CategoryRequirement.Special -> gate.isGranted(req.permission)
        }
    }

    // Re-poll grants on resume (covers returning from the system runtime dialog or the All Files
    // Access settings page without a process restart). Special perms aren't in the AppPermission
    // catalog, so resolve them here.
    fun refreshGrants() {
        grantedState.value = probeGrants()
    }

    // Enabling a permission-gated category persists ONLY after the permission is granted, so the
    // row never flashes "(needs permission)" while the dialog is still up. The label then appears
    // only in the genuine case — a previously-granted permission later revoked in system settings.
    // Disabling persists immediately.
    fun setCategoryEnabled(cat: ToolCategory, enabled: Boolean) {
        if (!enabled) {
            viewModelScope.launch { prefs.setToolCategoryEnabled(cat, false) }
            return
        }
        when (val req = requirementOf(cat)) {
            CategoryRequirement.None -> viewModelScope.launch { prefs.setToolCategoryEnabled(cat, true) }
            is CategoryRequirement.Runtime -> requestRuntime(cat, req.permission)
            is CategoryRequirement.Special -> requestSpecial(cat, req.permission)
        }
    }

    private fun requestRuntime(cat: ToolCategory, permission: AppPermission) {
        if (grantedState.value[cat] == true) {
            viewModelScope.launch { prefs.setToolCategoryEnabled(cat, true) }
            return
        }
        viewModelScope.launch {
            val result = gate.ensure(permission)
            grantedState.value = grantedState.value.toMutableMap().apply {
                put(cat, result.isGranted)
            }
            if (result.isGranted) {
                prefs.setToolCategoryEnabled(cat, true)
            } else {
                _events.emit(
                    CategoryPermissionEvent.Denied(
                        cat,
                        result.deniedMessage ?: permission.transientDeniedMessage,
                    ),
                )
            }
        }
    }

    // Special permission (All Files Access etc.): same flow as runtime, but the gate routes through
    // the rationale dialog → Settings page. Revert + snackbar on denial, exactly like runtime.
    private fun requestSpecial(cat: ToolCategory, special: SpecialPermission) {
        if (grantedState.value[cat] == true) {
            viewModelScope.launch { prefs.setToolCategoryEnabled(cat, true) }
            return
        }
        viewModelScope.launch {
            val result = gate.ensureSpecial(special)
            grantedState.value = grantedState.value.toMutableMap().apply {
                put(cat, result.isGranted)
            }
            if (result.isGranted) {
                prefs.setToolCategoryEnabled(cat, true)
            } else {
                _events.emit(
                    CategoryPermissionEvent.Denied(
                        cat,
                        result.deniedMessage ?: special.transientDeniedMessage,
                    ),
                )
            }
        }
    }

    fun setAskBeforeEachTool(enabled: Boolean) {
        viewModelScope.launch { prefs.set(ToolPrefs.AskBeforeEachTool, enabled) }
    }

    /** Stop always-allowing [toolName] — the confirm gate will ask again. */
    fun clearAlwaysAllow(toolName: String) {
        viewModelScope.launch {
            prefs.set(ToolPrefs.AlwaysAllowed, prefs.flow(ToolPrefs.AlwaysAllowed).first() - toolName)
        }
    }

    fun clearAllAlwaysAllow() {
        viewModelScope.launch { prefs.set(ToolPrefs.AlwaysAllowed, emptySet()) }
    }

    /** Stop blocking [toolName] — the confirm gate will ask again. */
    fun clearDeny(toolName: String) {
        viewModelScope.launch { prefs.set(ToolPrefs.Denied, prefs.flow(ToolPrefs.Denied).first() - toolName) }
    }

    fun clearAllDeny() {
        viewModelScope.launch { prefs.set(ToolPrefs.Denied, emptySet()) }
    }

    // --- Granted folders (filesystem) ---

    // Non-primary trees (SD/USB) unsupported in v1.
    fun onTreeUriPicked(treeUri: String, requestedKey: String? = null) {
        viewModelScope.launch {
            val resolved = folderResolver.resolve(treeUri) ?: run {
                transient.value = "Pick a folder under Internal storage."
                return@launch
            }
            val key = requestedKey?.takeIf { it.isNotBlank() } ?: dedupeKey(resolved.displayName)
            fsRoots.register(
                RegisteredRoot(
                    key = key,
                    displayName = resolved.displayName,
                    absolutePath = resolved.absolutePath,
                ),
            )
            transient.value = "Added '$key'"
        }
    }

    fun removeRoot(key: String) {
        viewModelScope.launch {
            fsRoots.unregister(key)
            transient.value = "Removed '$key'"
        }
    }

    fun acknowledgeTransient() {
        transient.value = null
    }

    private fun dedupeKey(base: String): String {
        val existing = fsRoots.snapshot().map { it.key }.toSet()
        if (base !in existing) return base
        var i = 2
        while ("${base}_$i" in existing) i++
        return "${base}_$i"
    }
}
