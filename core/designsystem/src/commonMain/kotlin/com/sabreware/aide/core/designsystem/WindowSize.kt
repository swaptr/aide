package com.sabreware.aide.core.designsystem

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.window.core.layout.WindowSizeClass as WindowMetrics

/**
 * App-wide width bucket — the single knob every responsive branch reads. Material breakpoints:
 * **Compact < 600dp · Medium 600–839dp · Expanded ≥ 840dp**. Computed once at the root
 * ([rememberAppWindowSizeClass]) and provided via [LocalWindowSizeClass]; branch UI on this, never on
 * platform (a desktop window resizes, a foldable/tablet changes size at runtime — size ≠ platform).
 */
enum class WindowSizeClass { Compact, Medium, Expanded }

/** Phone-width layout (hamburger drawer, bottom sheets, full-width content). */
val WindowSizeClass.isCompact: Boolean get() = this == WindowSizeClass.Compact

/** Tablet/desktop-width layout (pinned sidebar, capped content). Modals decide separately: [ModalPolicy]. */
val WindowSizeClass.isAtLeastMedium: Boolean get() = this != WindowSizeClass.Compact

val WindowSizeClass.isExpanded: Boolean get() = this == WindowSizeClass.Expanded

/** Default [WindowSizeClass.Compact] so any read off-root (previews, isolated tests, the voice overlay)
 *  degrades to the phone layout instead of crashing. The root re-provides the real value. */
val LocalWindowSizeClass = staticCompositionLocalOf { WindowSizeClass.Compact }

/**
 * The current width bucket from the official adaptive API (`currentWindowAdaptiveInfo()`), recomputed as
 * the window resizes. Widest-first so a wide window resolves to Expanded (not Medium). Call once at the
 * root and feed [LocalWindowSizeClass].
 */
@Composable
fun rememberAppWindowSizeClass(): WindowSizeClass {
    val metrics = currentWindowAdaptiveInfo().windowSizeClass
    return when {
        metrics.isWidthAtLeastBreakpoint(WindowMetrics.WIDTH_DP_EXPANDED_LOWER_BOUND) -> WindowSizeClass.Expanded
        metrics.isWidthAtLeastBreakpoint(WindowMetrics.WIDTH_DP_MEDIUM_LOWER_BOUND) -> WindowSizeClass.Medium
        else -> WindowSizeClass.Compact
    }
}
