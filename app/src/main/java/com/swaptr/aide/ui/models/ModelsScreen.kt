package com.swaptr.aide.ui.models

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swaptr.aide.R
import com.swaptr.aide.ui.common.AppPage

@Composable
fun ModelsScreen(
    onOpenDrawer: () -> Unit,
    viewModel: ModelsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.ensureNotificationPermission() }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.errorMessage) {
        val msg = state.errorMessage ?: return@LaunchedEffect
        val r = snackbarHostState.showSnackbar(msg, withDismissAction = true)
        if (r == SnackbarResult.Dismissed || r == SnackbarResult.ActionPerformed) {
            viewModel.dismissError()
        }
    }

    AppPage(
        title = "Models",
        navigationIcon = {
            IconButton(onClick = onOpenDrawer) {
                Icon(painterResource(R.drawable.ic_lc_menu), contentDescription = "Menu")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        scrollable = false,
    ) {
        ModelsContent(viewModel = viewModel)
    }
}
