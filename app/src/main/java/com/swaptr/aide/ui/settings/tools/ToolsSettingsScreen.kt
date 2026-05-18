package com.swaptr.aide.ui.settings.tools

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swaptr.aide.R
import com.swaptr.aide.data.prefs.ToolCategory
import com.swaptr.aide.ui.common.AppMenuEntry
import com.swaptr.aide.ui.common.AppMenuList
import com.swaptr.aide.ui.common.AppMenuToggle
import com.swaptr.aide.ui.common.AppPage

@Composable
fun ToolsSettingsScreen(
    onClose: () -> Unit,
    viewModel: ToolsSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshGrants(context)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is CategoryPermissionEvent.Denied -> {
                    val perms = event.perms
                        .map { it.substringAfterLast('.').lowercase().replace('_', ' ') }
                        .joinToString(", ")
                    val msg = if (perms.isEmpty()) {
                        "${event.cat.displayName} needs the permission you just declined."
                    } else {
                        "${event.cat.displayName} needs: $perms"
                    }
                    snackbarHostState.showSnackbar(msg, withDismissAction = true)
                }
            }
        }
    }

    AppPage(
        title = "Tools",
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(R.drawable.ic_lc_arrow_left),
                    contentDescription = "Back",
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) {
        AppMenuList(
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

        AppMenuList(
            title = "Categories",
            items = ToolCategory.values().map { cat ->
                val enabledPref = state.enabledByCategory[cat] == true
                val granted = state.grantedByCategory[cat] == true
                val visiblyOn = enabledPref && granted
                val needsPerm = enabledPref && !granted
                AppMenuEntry(
                    key = cat.name,
                    title = cat.displayName,
                    subtitle = if (needsPerm) "${cat.blurb} (needs permission)" else cat.blurb,
                    toggle = AppMenuToggle(
                        checked = visiblyOn,
                        onCheckedChange = { viewModel.setCategoryEnabled(cat, it) },
                    ),
                )
            },
        )
    }
}
