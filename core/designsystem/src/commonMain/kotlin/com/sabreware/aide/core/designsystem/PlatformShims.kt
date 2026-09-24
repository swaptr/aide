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
