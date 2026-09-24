package com.sabreware.aide.core.designsystem

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically

/**
 * **The** motion of chrome that changes in place: a header slot gaining or losing buttons, a page's tiles
 * folding away while it searches or selects. One duration and one easing, so the header, the band beside it
 * and the list under it move as one, in every host. Never hand-tune a separate spec for one of them.
 */
object ChromeMotion {
    const val DurationMs = 180

    fun <T> spec(): FiniteAnimationSpec<T> = tween(DurationMs, easing = FastOutSlowInEasing)

    /** A block appearing in a column: it opens its height while it fades in. */
    val enter: EnterTransition get() = expandVertically(spec()) + fadeIn(spec())

    /** A block leaving a column: it fades while it closes its height, so nothing below jumps. */
    val exit: ExitTransition get() = shrinkVertically(spec()) + fadeOut(spec())
}
