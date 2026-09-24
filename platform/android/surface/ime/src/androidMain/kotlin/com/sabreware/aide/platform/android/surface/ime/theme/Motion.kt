package com.sabreware.aide.platform.android.surface.ime.theme

import android.view.animation.OvershootInterpolator
import androidx.interpolator.view.animation.FastOutLinearInInterpolator
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

internal object Motion {
    const val popupFadeIn: Long = 220L
    const val popupFadeOut: Long = 180L
    const val pageCrossfade: Long = 220L
    const val slidePanel: Long = 220L
    const val popupSpring: Long = 120L
    const val popupScale: Long = 150L

    fun fastOutSlowIn() = FastOutSlowInInterpolator()
    fun fastOutLinearIn() = FastOutLinearInInterpolator()
    fun overshoot(tension: Float = 0.8f) = OvershootInterpolator(tension)
}
