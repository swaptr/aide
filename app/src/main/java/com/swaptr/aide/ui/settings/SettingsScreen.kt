package com.swaptr.aide.ui.settings

import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swaptr.aide.R
import com.swaptr.aide.ui.common.AppMenuEntry
import com.swaptr.aide.ui.common.AppMenuList
import com.swaptr.aide.ui.common.AppMenuToggle
import com.swaptr.aide.ui.common.AppPage
import com.swaptr.aide.ui.common.AppSheet

@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    onOpenWebSearch: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSpeech: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showRootsSheet by remember { mutableStateOf(false) }

    val allFilesAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { _ ->
        // The system intent doesn't return a useful result code — re-check the flag.
        viewModel.refreshPermissionState()
        if (!Environment.isExternalStorageManager()) {
            // User backed out without granting. Roll the toggle back so we don't lie
            // about being enabled.
            viewModel.setFilesystemToolEnabled(false)
        }
    }

    val openTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.onTreeUriPicked(uri)
    }

    LaunchedEffect(state.transientMessage) {
        val msg = state.transientMessage ?: return@LaunchedEffect
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        viewModel.acknowledgeTransient()
    }

    AppPage(
        title = "Settings",
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(R.drawable.ic_lc_arrow_left),
                    contentDescription = "Back",
                )
            }
        },
    ) {
        val entries = buildList {
            add(
                AppMenuEntry(
                    title = "Web search",
                    subtitle = "Pick the backend that powers the Web tool.",
                    onClick = onOpenWebSearch,
                ),
            )
            add(
                AppMenuEntry(
                    title = "Tools",
                    subtitle = "Disable tool categories or require approval before each run.",
                    onClick = onOpenTools,
                ),
            )
            add(
                AppMenuEntry(
                    title = "Voice",
                    subtitle = "Speech provider, on-device models, mic + reply toggles.",
                    onClick = onOpenSpeech,
                ),
            )
            add(
                AppMenuEntry(
                    title = "Filesystem tools",
                    subtitle = if (state.filesystemToolEnabled && !state.hasAllFilesAccess) {
                        "Grant All Files Access to continue."
                    } else {
                        "Allow the model to find, read, and rearrange files in folders you grant."
                    },
                    toggle = AppMenuToggle(
                        checked = state.filesystemToolEnabled,
                        onCheckedChange = { wanted ->
                            if (wanted && !Environment.isExternalStorageManager()) {
                                // Persist the user's intent immediately; we'll revert if
                                // they back out of the permission screen.
                                viewModel.setFilesystemToolEnabled(true)
                                allFilesAccessLauncher.launch(viewModel.allFilesAccessIntent())
                            } else {
                                viewModel.setFilesystemToolEnabled(wanted)
                            }
                        },
                    ),
                ),
            )
            if (state.filesystemToolEnabled && state.hasAllFilesAccess) {
                val grantedSubtitle = when (state.roots.size) {
                    0 -> "Tap to add a folder"
                    1 -> "1 folder granted"
                    else -> "${state.roots.size} folders granted"
                }
                add(
                    AppMenuEntry(
                        title = "Granted folders",
                        subtitle = grantedSubtitle,
                        leadingIconRes = R.drawable.ic_lc_folder_plus,
                        onClick = { showRootsSheet = true },
                    ),
                )
            }
        }
        AppMenuList(items = entries)
    }

    if (showRootsSheet) {
        GrantedFoldersSheet(
            onDismiss = { showRootsSheet = false },
            uiState = state,
            onRemove = viewModel::removeRoot,
            onPickFolder = { openTreeLauncher.launch(null) },
        )
    }
}

@Composable
private fun GrantedFoldersSheet(
    onDismiss: () -> Unit,
    uiState: SettingsUiState,
    onRemove: (String) -> Unit,
    onPickFolder: () -> Unit,
) {
    AppSheet(
        onDismiss = onDismiss,
        title = "Granted folders",
        subtitle = "The model can read and modify files only inside these folders.",
        contentPadding = PaddingValues(0.dp),
        contentSpacing = 0.dp,
    ) { controller ->
        val entries = buildList {
            uiState.roots.forEach { root ->
                add(
                    AppMenuEntry(
                        key = root.key,
                        title = root.key,
                        subtitle = root.absolutePath,
                        trailing = {
                            IconButton(onClick = { onRemove(root.key) }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_lc_trash),
                                    contentDescription = "Remove folder",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    ),
                )
            }
            add(
                AppMenuEntry(
                    title = "Add folder",
                    leadingIconRes = R.drawable.ic_lc_folder_plus,
                    onClick = { controller.close(andThen = onPickFolder) },
                ),
            )
        }
        AppMenuList(items = entries)
    }
}
