package com.sabreware.aide.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sabreware.aide.core.designsystem.AppDialog
import com.sabreware.aide.core.designsystem.AppMenu
import com.sabreware.aide.core.designsystem.AppMenuEntry
import com.sabreware.aide.core.designsystem.AppMenuLayout
import com.sabreware.aide.core.designsystem.AppMenuToggle
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.designsystem.state.UiState
import com.sabreware.aide.ui.settings.mcp.McpSettingsViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * The composer's "+" sheet — a short, content-sized menu of what can be added to a turn: attachment tiles,
 * per-turn toggles (Web search, Thinking), and a Connectors row. Tapping Connectors
 * closes this sheet and opens the unified connector flow ([com.sabreware.aide.ui.settings.mcp.ConnectorDialog]) —
 * the SAME pages the Settings screen renders full-screen, so there's one definition for both.
 */
@Composable
fun AddToChatSheet(
    onDismiss: () -> Unit,
    canAttachImage: Boolean,
    /** Whether this platform can STAGE a capture at all — see
     *  [com.sabreware.aide.core.common.media.ImageAttachmentStore.supportsCameraCapture]. The other half is
     *  [onCamera] being non-null, i.e. the host offering a capture launcher; the tile needs both. */
    cameraAvailable: Boolean,
    canAttachAudio: Boolean,
    /** Null on a host with no camera launcher. Omits the tile rather than disabling it. */
    onCamera: (() -> Unit)?,
    /** Null on a host with no image picker. Omits the tile rather than disabling it. */
    onPhotos: (() -> Unit)?,
    onRecordAudio: () -> Unit,
    onPickFile: () -> Unit,
    webSearchEnabled: Boolean,
    webSearchAvailable: Boolean,
    onToggleWebSearch: (Boolean) -> Unit,
    /**
     * The Thinking switch, or null when the model can't think — a switch that does nothing is not offered.
     */
    thinking: AppMenuToggle?,
    onOpenConnectors: () -> Unit,
    mcpViewModel: McpSettingsViewModel = koinViewModel(),
) {
    val mcp by mcpViewModel.uiState.collectAsStateWithLifecycle()
    // "…" while the store's first read resolves — never flash "None" at a user who has connectors.
    val connectorsSubtitle = when (val servers = mcp.servers) {
        UiState.Loading -> "…"
        is UiState.Failed -> "None"
        is UiState.Ready -> when (val n = servers.value.size) {
            0 -> "None"
            1 -> "1 connector"
            else -> "$n connectors"
        }
    }

    AppDialog(onDismiss = onDismiss, title = "Add to chat") { controller ->
        // Content owns insets (tiles row + AppMenuLists each inset 16dp); this column owns the 8dp item gap.
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // A scrolling rail of the same tiles the model grid uses — one component, one look.
        AppMenu(
            layout = AppMenuLayout.Rail(),
            items = listOfNotNull(
                // Omitted, not disabled: a disabled tile says "not right now", and on a platform with no
                // camera the honest answer is "not here".
                onCamera?.takeIf { cameraAvailable }?.let { capture ->
                    AppMenuEntry(
                        title = "Camera",
                        leadingIconRes = Res.drawable.ic_lc_camera,
                        enabled = canAttachImage,
                        onClick = { controller.close { capture() } },
                    )
                },
                onPhotos?.let { pick ->
                    AppMenuEntry(
                        title = "Photos",
                        leadingIconRes = Res.drawable.ic_lc_image,
                        enabled = canAttachImage,
                        onClick = { controller.close { pick() } },
                    )
                },
                // Recording an audio ATTACHMENT lives here with the other attachments, not on the mic
                // button — the mic transcribes into the composer, this produces a file the model receives.
                AppMenuEntry(
                    title = "Record",
                    leadingIconRes = Res.drawable.ic_lc_mic_vocal,
                    enabled = canAttachAudio,
                    onClick = { controller.close { onRecordAudio() } },
                ),
                // Any file. Always enabled — classification decides the route (image/audio/PDF/inline
                // text) and unsupported picks are refused WITH the reason at attach, not hidden here.
                AppMenuEntry(
                    title = "Files",
                    leadingIconRes = Res.drawable.ic_lc_folder,
                    onClick = { controller.close { onPickFile() } },
                ),
            ),
        )

        AppMenu(
            items = listOfNotNull(
                AppMenuEntry(
                    title = "Web search",
                    subtitle = if (webSearchAvailable) null else "This model can't search the web",
                    leadingIconRes = Res.drawable.ic_lc_globe,
                    enabled = webSearchAvailable,
                    toggle = AppMenuToggle(
                        checked = webSearchAvailable && webSearchEnabled,
                        onCheckedChange = onToggleWebSearch,
                        enabled = webSearchAvailable,
                    ),
                ),
                AppMenuEntry(
                    title = "Thinking",
                    subtitle = "Think before answering",
                    leadingIconRes = Res.drawable.ic_lc_brain,
                    toggle = thinking,
                ).takeIf { thinking != null },
            ),
        )

        AppMenu(
            items = listOf(
                AppMenuEntry(
                    title = "Connectors",
                    subtitle = connectorsSubtitle,
                    leadingIconRes = Res.drawable.ic_mcp,
                    // Close this menu, then open the unified connector sheet (no sheet nesting).
                    onClick = { controller.close { onOpenConnectors() } },
                ),
            ),
        )
        }
    }
}
