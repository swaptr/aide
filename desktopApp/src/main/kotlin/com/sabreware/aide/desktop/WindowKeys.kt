package com.sabreware.aide.desktop

import com.sabreware.aide.core.common.prefs.Tier
import com.sabreware.aide.core.common.prefs.stringKey
import com.sabreware.aide.core.designsystem.state.UiState

/** Desktop-only: declared in the desktop source set, so no other target compiles it. */
object WindowKeys {
    /** `"w,h,x,y"` in dp; `x`/`y` = [UNPLACED] until the OS has placed the window once. */
    val Bounds = stringKey("ui.window_bounds", default = "", tier = Tier.UiState)
}

/** "Not positioned yet" — let the OS place the window rather than pinning it to 0,0. */
const val UNPLACED = Float.MIN_VALUE

data class WindowBounds(val width: Float, val height: Float, val x: Float, val y: Float) {
    fun encode(): String = "$width,$height,$x,$y"

    companion object {
        val Default = WindowBounds(width = 1180f, height = 800f, x = UNPLACED, y = UNPLACED)

        fun decode(raw: String): WindowBounds {
            val p = raw.split(',').mapNotNull { it.toFloatOrNull() }
            if (p.size != 4) return Default
            // A zero/negative size would open an invisible window — treat it as never-saved.
            if (p[0] < MIN_SIZE || p[1] < MIN_SIZE) return Default
            return WindowBounds(p[0], p[1], p[2], p[3])
        }

        private const val MIN_SIZE = 200f
    }
}
