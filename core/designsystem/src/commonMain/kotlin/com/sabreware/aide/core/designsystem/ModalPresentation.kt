package com.sabreware.aide.core.designsystem

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.window.core.layout.WindowSizeClass as WindowMetrics

/** The two shapes an [AppDialog] can take: a bottom sheet, or a centered dialog card. */
enum class ModalPresentation { Sheet, Dialog }

/**
 * Which [ModalPresentation] an application prefers. **Policy is injected data**: each application provides
 * its own through [LocalModalPolicy], and [AppDialog] never asks what platform it is on.
 *
 * Resolution alone cannot tell a phone from a desktop, so neither value tries to. [Adaptive] is for touch
 * hosts: a bottom sheet is the reachable, drag-to-dismiss surface a thumb wants, and it stays a sheet until the
 * window is large in BOTH dimensions (a tablet, an unfolded foldable, a desktop-mode window). Checking height
 * as well as width is what keeps a phone in landscape on a sheet: it is wide, but never taller than Material's
 * compact-height bound (480dp), and a centered card squeezed into that height is exactly what clipped.
 * [Dialog] is for pointer hosts: a desktop window is a dialog at every size, since a drag-handle sheet is a
 * touch affordance and a mouse user reads a centered card more comfortably.
 */
enum class ModalPolicy { Adaptive, Dialog }

/** The host's [ModalPolicy]. Defaults to [ModalPolicy.Adaptive], so a host that provides nothing (the voice
 *  overlay, previews) gets the touch behaviour. */
val LocalModalPolicy = staticCompositionLocalOf { ModalPolicy.Adaptive }

/** The resolved presentation for the current window — provided once at the root by [rememberModalPresentation]. */
val LocalModalPresentation = staticCompositionLocalOf { ModalPresentation.Sheet }

/**
 * Resolves [policy] against the live window (recomputed as it resizes or rotates). Call once at the root,
 * next to [rememberAppWindowSizeClass], and feed [LocalModalPresentation].
 */
@Composable
fun rememberModalPresentation(policy: ModalPolicy = LocalModalPolicy.current): ModalPresentation {
    val metrics = currentWindowAdaptiveInfo().windowSizeClass
    return modalPresentationFor(policy, metrics.minWidthDp, metrics.minHeightDp)
}

/**
 * The whole decision, as a pure function of the policy and the window's size-class lower bounds (dp) — so it is
 * unit-tested rather than trusted. [ModalPolicy.Adaptive] is a dialog only when the window reaches Material's
 * MEDIUM bound on both axes (600dp wide AND 480dp tall).
 */
fun modalPresentationFor(policy: ModalPolicy, widthDp: Int, heightDp: Int): ModalPresentation =
    when (policy) {
        ModalPolicy.Dialog -> ModalPresentation.Dialog
        ModalPolicy.Adaptive -> {
            val roomy = widthDp >= WindowMetrics.WIDTH_DP_MEDIUM_LOWER_BOUND &&
                heightDp >= WindowMetrics.HEIGHT_DP_MEDIUM_LOWER_BOUND
            if (roomy) ModalPresentation.Dialog else ModalPresentation.Sheet
        }
    }
