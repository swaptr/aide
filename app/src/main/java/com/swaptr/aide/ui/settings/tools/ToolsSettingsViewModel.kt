package com.swaptr.aide.ui.settings.tools

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swaptr.aide.data.prefs.ToolCategory
import com.swaptr.aide.data.prefs.UserPreferencesRepository
import com.swaptr.aide.permission.RuntimePermissionGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// Switch UI is enabled && granted so a revoked perm flips row OFF without a stale pref masking.
data class ToolsSettingsUiState(
    val enabledByCategory: Map<ToolCategory, Boolean> = ToolCategory.values().associateWith { false },
    val grantedByCategory: Map<ToolCategory, Boolean> = ToolCategory.values().associateWith { true },
    val askBeforeEachTool: Boolean = false,
)

sealed class CategoryPermissionEvent {
    /** Surfaced to the screen for snackbar feedback after the user denies a runtime permission. */
    data class Denied(val cat: ToolCategory, val perms: List<String>) : CategoryPermissionEvent()
}

// Persist intent first; screen calls revertCategoryEnabled on user back-out so we don't lie about ON.
@HiltViewModel
class ToolsSettingsViewModel @Inject constructor(
    private val prefs: UserPreferencesRepository,
    private val permissionGate: RuntimePermissionGate,
) : ViewModel() {

    private val grantedState =
        MutableStateFlow<Map<ToolCategory, Boolean>>(
            ToolCategory.values().associateWith { it.requirement() is CategoryRequirement.None },
        )

    private val _events = MutableSharedFlow<CategoryPermissionEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    val uiState: StateFlow<ToolsSettingsUiState> = combine(
        prefs.enabledToolCategoriesFlow,
        prefs.askBeforeEachToolFlow,
        grantedState,
    ) { enabled, ask, granted ->
        ToolsSettingsUiState(
            enabledByCategory = ToolCategory.values().associateWith { it in enabled },
            grantedByCategory = granted,
            askBeforeEachTool = ask,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ToolsSettingsUiState())

    fun refreshGrants(context: Context) {
        grantedState.value = ToolCategory.values().associateWith {
            context.isCategoryGranted(it.requirement())
        }
    }

    // Persist intent first, then trigger permission flow; screen reverts on denial.
    fun setCategoryEnabled(cat: ToolCategory, enabled: Boolean) {
        viewModelScope.launch { prefs.setToolCategoryEnabled(cat, enabled) }
        if (!enabled) return
        when (val req = cat.requirement()) {
            CategoryRequirement.None -> Unit
            is CategoryRequirement.Runtime -> requestRuntime(cat, req.perms)
        }
    }

    private fun requestRuntime(cat: ToolCategory, perms: List<String>) {
        if (grantedState.value[cat] == true) return
        viewModelScope.launch {
            val outcome = permissionGate.requestAll(perms)
            val denied = perms.filter { !outcome.granted(it) }
            grantedState.value = grantedState.value.toMutableMap().apply {
                put(cat, outcome.allGranted)
            }
            if (!outcome.allGranted) {
                prefs.setToolCategoryEnabled(cat, false)
                _events.emit(CategoryPermissionEvent.Denied(cat, denied))
            }
        }
    }

    fun setAskBeforeEachTool(enabled: Boolean) {
        viewModelScope.launch { prefs.setAskBeforeEachTool(enabled) }
    }
}
