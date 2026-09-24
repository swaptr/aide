package com.sabreware.aide.core.designsystem

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * How navigation transitions feel. This is one of the few places where branching on **platform** rather than
 * window size is correct: it's about input modality, not available space.
 *
 * - [Slide] — touch platforms. The directional travel mirrors the swipe that triggered it, and Android's
 *   predictive-back gesture *scrubs* that slide frame-by-frame, so the motion is part of the interaction.
 * - [Fade] — pointer platforms. There is no gesture to mirror and no predictive back to scrub, so a
 *   full-width slide is just latency on a mouse click. A short **fade through** instead: quick, but still
 *   animated, because navigating with no transition at all reads as two frames stamped over each other.
 */
enum class NavStyle { Slide, Fade }

/** The platform's navigation feel (Android: [NavStyle.Slide], desktop: [NavStyle.Fade]). */
internal expect val navStyle: NavStyle

/**
 * The app's one navigation transition — used by the app's NavDisplay (screens) and by a modal flow's pages
 * ([com.sabreware.aide.core.designsystem.navigation.ModalSceneStrategy]) so the two feel identical. `forward` slides new
 * content in from the trailing edge; pops reverse it. [NavStyle.Fade] has no travel, so `forward` is ignored.
 */
object NavMotion {
    // Material "fade through": the outgoing screen fades out FIRST, and only then does the incoming one fade
    // in (note the delayMillis). They are deliberately NOT simultaneous — a plain crossfade composites both
    // destinations in the same frames, which is what makes them look stamped over one another. Sequencing
    // means at most one is ever visible, so the swap stays clean while the whole thing still fits in ~180ms.
    private const val FadeOutMs = 70
    private const val FadeInMs = 110

    /** Total transition time. Also drives the in-dialog page `SizeTransform`, so the card resizes in step. */
    val DurationMs: Int = if (navStyle == NavStyle.Fade) FadeOutMs + FadeInMs else 320

    val Easing: Easing =
        if (navStyle == NavStyle.Fade) FastOutSlowInEasing else CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun enter(forward: Boolean): EnterTransition = when (navStyle) {
        // Decelerate in, after the outgoing screen has cleared.
        NavStyle.Fade -> fadeIn(tween(FadeInMs, delayMillis = FadeOutMs, easing = LinearOutSlowInEasing))
        NavStyle.Slide ->
            slideInHorizontally(tween(DurationMs, easing = Easing)) { full -> if (forward) full else -full } +
                fadeIn(tween(DurationMs, easing = Easing))
    }

    fun exit(forward: Boolean): ExitTransition = when (navStyle) {
        // Accelerate out — gets the old content off screen quickly so the incoming fade owns most of the time.
        NavStyle.Fade -> fadeOut(tween(FadeOutMs, easing = FastOutLinearInEasing))
        NavStyle.Slide ->
            slideOutHorizontally(tween(DurationMs, easing = Easing)) { full -> if (forward) -full else full } +
                fadeOut(tween(DurationMs, easing = Easing))
    }
}
