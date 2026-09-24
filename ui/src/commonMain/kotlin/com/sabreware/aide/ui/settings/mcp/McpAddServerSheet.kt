package com.sabreware.aide.ui.settings.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sabreware.aide.core.designsystem.AppNotice
import com.sabreware.aide.core.designsystem.AppTextField
import com.sabreware.aide.core.designsystem.ExpandableSection
import com.sabreware.aide.core.designsystem.form.FormActions
import org.koin.compose.viewmodel.koinViewModel

/**
 * The add-connector form body (no sheet chrome): address + an optional key. Host-agnostic, so it
 * can be a pushed page inside any sheet. [McpSettingsViewModel] persists only on success.
 * [onConnected] fires once the connect resolves successfully; [onCancel] backs out.
 *
 * The key is an [ExpandableSection]: expanding it is the "needs a key" choice and reveals the key field, with
 * the name it is sent as (Authorization by default) under it; collapsed, no key is sent.
 */
@Composable
fun McpAddServerForm(
    onCancel: () -> Unit,
    onConnected: () -> Unit,
    modifier: Modifier = Modifier,
    initialUrl: String = "",
    viewModel: McpSettingsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var useHeader by rememberSaveable { mutableStateOf(false) }
    var headerName by rememberSaveable { mutableStateOf("Authorization") }
    var headerValue by rememberSaveable { mutableStateOf("") }
    var submitting by rememberSaveable { mutableStateOf(false) }

    // The VM sets busy=true synchronously on submit; when it flips back to false the connect
    // resolved — leave on success, surface the error otherwise.
    LaunchedEffect(state.busy) {
        if (submitting && !state.busy) {
            submitting = false
            if (state.error == null) onConnected()
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppTextField(
            value = url,
            onValueChange = { url = it; if (state.error != null) viewModel.clearError() },
            label = "Address",
            placeholder = "https://example.com/mcp",
            singleLine = true,
        )

        ExpandableSection(
            title = "Needs a key",
            expanded = useHeader,
            onExpandedChange = { useHeader = it },
            cardPadding = PaddingValues(0.dp),
        ) {
            // ExpandableSection's body is full-bleed, so this form content brings its own inset. The header
            // row's bottom inset already sits above it, so the top is small and the bottom matches the sides.
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppTextField(
                    value = headerValue,
                    onValueChange = { headerValue = it },
                    label = "Key",
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                // Most servers take the key as Authorization; the rare one that wants another name says so.
                AppTextField(
                    value = headerName,
                    onValueChange = { headerName = it },
                    label = "Sent as",
                    singleLine = true,
                )
            }
        }

        AppNotice(if (submitting) null else state.error)

        FormActions(
            confirmLabel = if (submitting) "Connecting…" else "Connect",
            confirmEnabled = url.isNotBlank() && !submitting,
            onCancel = onCancel,
            onConfirm = {
                submitting = true
                viewModel.addServer(url, if (useHeader) headerName else "", if (useHeader) headerValue else "")
            },
        )
    }
}
