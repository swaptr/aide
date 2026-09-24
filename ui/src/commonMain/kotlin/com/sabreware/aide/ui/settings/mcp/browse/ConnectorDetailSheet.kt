package com.sabreware.aide.ui.settings.mcp.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.AppMenu
import com.sabreware.aide.core.designsystem.AppMenuEntry
import com.sabreware.aide.core.designsystem.AppMenuLayout
import com.sabreware.aide.core.designsystem.AppNotice
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.domain.connector.Connector
import com.sabreware.aide.core.domain.connector.ConnectorAuthType
import com.sabreware.aide.ui.models.DetailRow

/**
 * One catalog connector, in the item-sheet shape: what can be done now in a rail (Connect, or Disconnect once
 * added), any error, what it is, then its facts. Host-agnostic — the host supplies the chrome (its name is the
 * header) and decides what Connect does (the sign-in kind is [Connector.authType]).
 */
@Composable
fun ConnectorDetailContent(
    connector: Connector,
    installed: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    // Operation error (e.g. a failed connect) — shown under the rail. Null = nothing.
    error: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppMenu(
            items = listOf(
                if (installed) {
                    AppMenuEntry(
                        key = "disconnect",
                        title = "Disconnect",
                        leadingIconRes = Res.drawable.ic_lc_unlink,
                        destructive = true,
                        onClick = onDisconnect,
                    )
                } else {
                    AppMenuEntry(
                        key = "connect",
                        title = if (busy) "Connecting…" else connectLabel(connector.authType),
                        leadingIconRes = Res.drawable.ic_lc_plug,
                        enabled = !busy,
                        onClick = onConnect,
                    )
                },
            ),
            layout = AppMenuLayout.Rail(),
        )
        AppNotice(error, modifier = Modifier.padding(horizontal = 20.dp))
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (connector.description.isNotBlank()) {
                Text(connector.description, style = MaterialTheme.typography.bodyMedium)
            }
            DetailRow("Kind", connector.category.label)
            DetailRow("Sign-in", authLabel(connector.authType))
            DetailRow("Address", connector.serverUrl)
        }
    }
}

private fun authLabel(auth: ConnectorAuthType): String = when (auth) {
    ConnectorAuthType.NONE -> "None needed"
    ConnectorAuthType.HEADER -> "Needs a key"
    ConnectorAuthType.OAUTH -> "Sign in with your account"
    ConnectorAuthType.UNKNOWN -> "Checked when you connect"
}

private fun connectLabel(auth: ConnectorAuthType): String = when (auth) {
    ConnectorAuthType.NONE -> "Connect"
    ConnectorAuthType.HEADER -> "Add key"
    ConnectorAuthType.OAUTH, ConnectorAuthType.UNKNOWN -> "Sign in"
}
