package com.sabreware.aide.ui.settings.speech

import androidx.compose.runtime.Composable
import com.sabreware.aide.core.designsystem.AppDialog
import com.sabreware.aide.core.designsystem.AppMenu
import com.sabreware.aide.core.designsystem.AppMenuEntry
import com.sabreware.aide.core.domain.connection.ConnectionKind
import com.sabreware.aide.core.domain.connection.ProviderInfo
import com.sabreware.aide.core.domain.model.ProviderId

/** Display title for a voice-engine preference (`null` = Auto): the engine's or the connection's name. */
internal fun voiceProviderTitle(pref: ProviderInfo?): String = pref?.name ?: "Auto"

/** One line on what picking this engine means — where the audio goes is the fact a user needs. */
internal fun voiceProviderSubtitle(info: ProviderInfo): String = when (info.kind) {
    ConnectionKind.OnDevice -> "Runs on this device. Works offline."
    ConnectionKind.SelfHosted -> "${info.vendorName ?: "Self-hosted"}. Audio stays on your network."
    ConnectionKind.Cloud -> "${info.vendorName ?: "Cloud"}. Audio leaves the device."
}

/**
 * Voice-engine picker — the reusable [AppDialog] with a single-select list, like every single-choice
 * picker: tapping a row applies it and closes the sheet. [selected] is the current preference
 * (`null` = Auto).
 *
 * [providers] is whatever the running application contributed to the speech registry, in binding
 * order — the sheet names no engine itself, so a target that lacks one never shows a dead row and a
 * vendor added in `:di` appears here without an edit.
 */
@Composable
fun VoiceProviderSheet(
    selected: ProviderId?,
    providers: List<ProviderInfo>,
    onSelect: (ProviderId?) -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismiss = onDismiss,
        title = "Voice engine",
    ) { controller ->
        AppMenu(
            items = buildList {
                add(
                    AppMenuEntry(
                        title = voiceProviderTitle(null),
                        subtitle = "Your cloud voice if you set one, else on-device.",
                        selected = selected == null,
                        onClick = { onSelect(null); controller.close() },
                    ),
                )
                providers.forEach { info ->
                    add(
                        AppMenuEntry(
                            key = info.id.value,
                            title = voiceProviderTitle(info),
                            subtitle = voiceProviderSubtitle(info),
                            selected = selected == info.id,
                            onClick = { onSelect(info.id); controller.close() },
                        ),
                    )
                }
            },
        )
    }
}
