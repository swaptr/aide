package com.swaptr.aide.ui.models.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.swaptr.aide.data.provider.OllamaConfig
import com.swaptr.aide.ui.common.AppSheet
import com.swaptr.aide.ui.common.SwipeableTabbedContent

private val MODES = listOf("Self-hosted", "Cloud")
private const val MODE_SELF_HOSTED = 0
private const val MODE_CLOUD = 1

@Composable
fun OllamaEditDialog(
    initial: OllamaConfig?,
    onDismiss: () -> Unit,
    onSave: (OllamaConfig) -> Unit,
) {
    var modeIndex by rememberSaveable {
        mutableIntStateOf(if (initial?.isCloud == true) MODE_CLOUD else MODE_SELF_HOSTED)
    }
    var baseUrl by rememberSaveable {
        mutableStateOf(initial?.takeUnless { it.isCloud }?.baseUrl.orEmpty())
    }
    var cloudToken by rememberSaveable {
        mutableStateOf(if (initial?.isCloud == true) initial.authToken.orEmpty() else "")
    }
    var selfHostedToken by rememberSaveable {
        mutableStateOf(if (initial?.isCloud == false) initial.authToken.orEmpty() else "")
    }

    val isCloud = modeIndex == MODE_CLOUD
    val canSave = if (isCloud) cloudToken.isNotBlank() else baseUrl.isNotBlank()

    AppSheet(
        onDismiss = onDismiss,
        title = "Connect to Ollama",
        contentPadding = PaddingValues(0.dp),
        contentSpacing = 0.dp,
    ) { controller ->
        SwipeableTabbedContent(
            tabs = MODES,
            selectedIndex = modeIndex,
            onSelectIndex = { modeIndex = it },
            containerColor = Color.Transparent,
        ) { page ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (page) {
                    MODE_CLOUD -> CloudForm(
                        token = cloudToken,
                        onTokenChange = { cloudToken = it },
                    )
                    else -> SelfHostedForm(
                        baseUrl = baseUrl,
                        onBaseUrlChange = { baseUrl = it },
                        token = selfHostedToken,
                        onTokenChange = { selfHostedToken = it },
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { controller.close() }) { Text("Cancel") }
            Spacer(Modifier.size(8.dp))
            TextButton(
                enabled = canSave,
                onClick = {
                    val resolvedBase = if (isCloud) OllamaConfig.CLOUD_BASE_URL else baseUrl.trim()
                    val activeToken = if (isCloud) cloudToken else selfHostedToken
                    val resolvedToken = activeToken.trim().takeIf { it.isNotBlank() }
                    controller.close {
                        onSave(
                            OllamaConfig(
                                baseUrl = resolvedBase,
                                authToken = resolvedToken,
                                isCloud = isCloud,
                            ),
                        )
                    }
                },
            ) { Text("Save") }
        }
    }
}

@Composable
private fun CloudForm(
    token: String,
    onTokenChange: (String) -> Unit,
) {
    val linkStyle = TextLinkStyles(
        style = SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
            textDecoration = TextDecoration.Underline,
        ),
    )
    val description = buildAnnotatedString {
        append("Connects to ")
        withLink(LinkAnnotation.Url("https://ollama.com", linkStyle)) { append("ollama.com") }
        append(". Generate an API key at ")
        withLink(LinkAnnotation.Url("https://ollama.com/settings/keys", linkStyle)) {
            append("ollama.com/settings/keys")
        }
        append(".")
    }
    Text(
        text = description,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = token,
        onValueChange = onTokenChange,
        label = { Text("API key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SelfHostedForm(
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    token: String,
    onTokenChange: (String) -> Unit,
) {
    Text(
        "Enter your Ollama server's address. The default port is 11434.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = baseUrl,
        onValueChange = onBaseUrlChange,
        label = { Text("Base URL") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = token,
        onValueChange = onTokenChange,
        label = { Text("Bearer token (optional)") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}
