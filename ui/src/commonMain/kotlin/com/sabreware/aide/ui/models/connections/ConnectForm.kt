package com.sabreware.aide.ui.models.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.AppNotice
import com.sabreware.aide.core.designsystem.AppTextField
import com.sabreware.aide.core.designsystem.NoticeSeverity
import com.sabreware.aide.core.designsystem.form.FormActions
import com.sabreware.aide.core.domain.connection.ConnectionDraft
import com.sabreware.aide.core.domain.connection.ServiceDescriptor

/** What an edit form starts from; null for a new connection. */
data class ConnectFormInitial(val baseUrl: String, val apiKey: String?)

/**
 * The one connect form, for every service: a Name (new connections only — an existing one is renamed like
 * anything else, through its label), the base URL (pre-filled from the service; the user's to type for a
 * custom endpoint) and the key, with a link to where the service hands keys out. Drives no navigation; the
 * host decides what happens after [onSubmit].
 */
@Composable
fun ConnectForm(
    service: ServiceDescriptor,
    initial: ConnectFormInitial?,
    onSubmit: (ConnectionDraft) -> Unit,
    onCancel: (() -> Unit)?,
    modifier: Modifier = Modifier,
    notice: String? = null,
) {
    val creating = initial == null
    var baseUrl by rememberSaveable(service.id) { mutableStateOf(initial?.baseUrl ?: service.baseUrl) }
    var apiKey by rememberSaveable(service.id) { mutableStateOf(initial?.apiKey.orEmpty()) }
    var name by rememberSaveable(service.id) { mutableStateOf("") }
    var attempted by rememberSaveable(service.id) { mutableStateOf(false) }
    val keyMissing = service.requiresKey && apiKey.isBlank()
    val error = when {
        !attempted -> null
        baseUrl.isBlank() -> "Enter a base URL."
        keyMissing -> "Enter an API key."
        else -> null
    }
    val uriHandler = LocalUriHandler.current

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        service.blurb?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (creating) {
            AppTextField(value = name, onValueChange = { name = it }, label = "Name", placeholder = service.name, singleLine = true)
        }
        AppTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = "Base URL",
            placeholder = if (service.custom) "http://192.168.1.10:8000/v1" else null,
            singleLine = true,
        )
        AppTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = if (service.requiresKey) "API key" else "API key (optional)",
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        service.helpUrl?.let { url ->
            TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) { Text("Get an API key") }
        }
        if (error != null) AppNotice(error) else AppNotice(notice, severity = NoticeSeverity.Info)
        FormActions(
            confirmLabel = if (creating) "Connect" else "Save",
            onCancel = onCancel,
            onConfirm = {
                attempted = true
                if (baseUrl.isNotBlank() && !keyMissing) {
                    onSubmit(
                        ConnectionDraft(
                            vendor = service.vendor,
                            label = name.trim().ifEmpty { service.name },
                            baseUrl = baseUrl.trim(),
                            apiKey = apiKey.trim().takeIf { it.isNotEmpty() },
                        ),
                    )
                }
            },
        )
    }
}
