package com.sabreware.aide.core.designsystem

import androidx.compose.runtime.Composable

/** A tap-haptic tick. Android: `View.performHapticFeedback(VIRTUAL_KEY)`. Desktop: no-op. */
@Composable
expect fun rememberHapticTick(): () -> Unit

/** The system animator duration scale (1f normal, 0f = animations disabled). Android reads
 *  `Settings.Global.ANIMATOR_DURATION_SCALE`; desktop returns 1f. */
@Composable
expect fun animatorDurationScale(): Float

/** A transient-message toaster. Android: `Toast`. Desktop: logs. */
@Composable
expect fun rememberToaster(): (String) -> Unit

/**
 * The platform window that hosts [AppDialog]. Android: a full-screen [androidx.compose.ui.window.Dialog]
 * with `decorFitsSystemWindows=false` + edge-to-edge window config (so the keyboard arrives as a
 * `WindowInsets.ime` inset). Desktop: a plain full-width Dialog. [onDismiss] fires on the platform
 * dismiss request (back / outside is disabled by AppDialog, which drives its own close).
 */
@Composable
expect fun AppDialogWindow(onDismiss: () -> Unit, content: @Composable () -> Unit)
