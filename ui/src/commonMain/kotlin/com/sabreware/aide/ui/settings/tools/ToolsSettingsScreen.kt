package com.sabreware.aide.ui.settings.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sabreware.aide.core.designsystem.AppDialog
import com.sabreware.aide.core.designsystem.AppListItem
import com.sabreware.aide.core.designsystem.AppMenu
import com.sabreware.aide.core.designsystem.AppMenuEntry
import com.sabreware.aide.core.designsystem.AppMenuEntryAction
import com.sabreware.aide.core.designsystem.AppMenuSectionTitle
import com.sabreware.aide.core.designsystem.AppMenuToggle
import com.sabreware.aide.core.designsystem.AppPage
import com.sabreware.aide.core.designsystem.rememberToaster
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.domain.permission.CategoryRequirement
import com.sabreware.aide.core.domain.tools.ToolCategory
import com.sabreware.aide.core.domain.tools.Toolset
import com.sabreware.aide.core.domain.tools.fs.RegisteredRoot
import com.sabreware.aide.ui.platform.LocalPlatformAffordances
import com.sabreware.aide.ui.settings.websearch.WebSearchProviderSheet
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ToolsSettingsScreen(
    viewModel: ToolsSettingsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toaster = rememberToaster()
    val snackbarHostState = remember { SnackbarHostState() }
    var showRootsSheet by rememberSaveable { mutableStateOf(false) }
    var showWebSheet by rememberSaveable { mutableStateOf(false) }

    // Null on a host with no directory dialog: the "Add folder" row is then omitted rather than dead.
    val openFolderPicker = LocalPlatformAffordances.current.folderPicker
        ?.rememberLauncher { treeUri -> viewModel.onTreeUriPicked(treeUri) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshGrants()
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is CategoryPermissionEvent.Denied ->
                    snackbarHostState.showSnackbar(event.message, withDismissAction = true)
            }
        }
    }

    LaunchedEffect(state.transientMessage) {
        val msg = state.transientMessage ?: return@LaunchedEffect
        toaster(msg)
        viewModel.acknowledgeTransient()
    }

    AppPage(
        title = "Tools",
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) {
        AppMenu(
            title = "Permissions",
            items = listOf(
                AppMenuEntry(
                    title = "Ask before each tool run",
                    subtitle = "Confirm every tool call. Chat only.",
                    toggle = AppMenuToggle(
                        checked = state.askBeforeEachTool,
                        onCheckedChange = viewModel::setAskBeforeEachTool,
                    ),
                ),
            ),
        )

        // Reset the per-tool "always allow" choices (made via the chat confirm dialog's checkbox).
        if (state.alwaysAllowedTools.isNotEmpty()) {
            AppMenuSectionTitle("Always-allowed tools")
            AppMenu(
                items = state.alwaysAllowedTools.sorted().map { name ->
                    AppMenuEntry(
                        title = name,
                        subtitle = "Tap to ask again next time.",
                        onClick = { viewModel.clearAlwaysAllow(name) },
                    )
                } + AppMenuEntry(title = "Clear all", onClick = viewModel::clearAllAlwaysAllow),
            )
        }

        if (state.deniedTools.isNotEmpty()) {
            AppMenuSectionTitle("Never-allowed tools")
            AppMenu(
                items = state.deniedTools.sorted().map { name ->
                    AppMenuEntry(
                        title = name,
                        subtitle = "Tap to allow asking again.",
                        onClick = { viewModel.clearDeny(name) },
                    )
                } + AppMenuEntry(title = "Clear all", onClick = viewModel::clearAllDeny),
            )
        }

        // Tools gated by a normal runtime permission (or none) — a tap toggles them directly. Web
        // additionally carries a config button (provider picker) once enabled.
        AppMenuSectionTitle("Simple permissions")
        AppMenu(
            items = state.toolsets
                .filter { it.requirement !is CategoryRequirement.Special }
                .map { toolset ->
                    permissionEntry(
                        toolset = toolset,
                        state = state,
                        onToggle = { on -> viewModel.setCategoryEnabled(toolset.category, on) },
                        action = AppMenuEntryAction(
                            iconRes = Res.drawable.ic_lc_settings,
                            contentDescription = "Web search provider",
                            onClick = { showWebSheet = true },
                        ).takeIf {
                            toolset.category == ToolCategory.Web && state.isVisiblyOn(toolset.category)
                        },
                    )
                },
        )

        // Tools gated by a special system permission (Settings page), e.g. Filesystem → All Files
        // Access. Filesystem carries a badged folder-manager button (left of the toggle) once
        // enabled + granted, opening the granted-folders sheet.
        AppMenuSectionTitle("Special permissions")
        AppMenu(
            items = state.toolsets
                .filter { it.requirement is CategoryRequirement.Special }
                .map { toolset ->
                    permissionEntry(
                        toolset = toolset,
                        state = state,
                        onToggle = { on -> viewModel.setCategoryEnabled(toolset.category, on) },
                        action = AppMenuEntryAction(
                            iconRes = Res.drawable.ic_lc_folder_plus,
                            contentDescription = "Granted folders (${state.roots.size})",
                            badgeCount = state.roots.size,
                            onClick = { showRootsSheet = true },
                        ).takeIf {
                            toolset.category == ToolCategory.Filesystem &&
                                state.isVisiblyOn(toolset.category)
                        },
                    )
                },
        )
    }

    if (showRootsSheet) {
        GrantedFoldersSheet(
            onDismiss = { showRootsSheet = false },
            roots = state.roots,
            onRemove = viewModel::removeRoot,
            onPickFolder = openFolderPicker,
        )
    }

    if (showWebSheet) {
        WebSearchProviderSheet(onDismiss = { showWebSheet = false })
    }
}


// One permission row as DATA: title, state-aware subtitle, the switch, and (optionally) the config
// button that sits left of it. No bespoke row composable — AppMenu renders all of them.
private fun permissionEntry(
    toolset: Toolset,
    state: ToolsSettingsUiState,
    onToggle: (Boolean) -> Unit,
    action: AppMenuEntryAction?,
): AppMenuEntry {
    val cat = toolset.category
    val enabledPref = state.enabledByCategory[cat] == true
    val granted = state.grantedByCategory[cat] == true
    return AppMenuEntry(
        key = cat.id,
        title = toolset.displayName,
        subtitle = if (enabledPref && !granted) "${toolset.blurb} (needs permission)" else toolset.blurb,
        toggle = AppMenuToggle(checked = enabledPref && granted, onCheckedChange = onToggle),
        action = action,
    )
}

// A category reads as ON only when the user enabled it AND the OS granted it.
private fun ToolsSettingsUiState.isVisiblyOn(cat: ToolCategory): Boolean =
    enabledByCategory[cat] == true && grantedByCategory[cat] == true

@Composable
private fun GrantedFoldersSheet(
    onDismiss: () -> Unit,
    roots: List<RegisteredRoot>,
    onRemove: (String) -> Unit,
    /** Null where the host offers no directory dialog — the "Add folder" row is omitted. */
    onPickFolder: (() -> Unit)?,
) {
    AppDialog(
        onDismiss = onDismiss,
        title = "Granted folders",
    ) { controller ->
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            roots.forEach { root ->
                AppListItem(
                    headline = root.key,
                    supportingText = root.absolutePath,
                    trailing = {
                        IconButton(onClick = { onRemove(root.key) }) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_lc_trash),
                                contentDescription = "Remove folder",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }
            if (onPickFolder != null) {
                AppListItem(
                    headline = "Add folder",
                    leadingIconRes = Res.drawable.ic_lc_folder_plus,
                    onClick = { controller.close(andThen = onPickFolder) },
                )
            }
        }
    }
}
