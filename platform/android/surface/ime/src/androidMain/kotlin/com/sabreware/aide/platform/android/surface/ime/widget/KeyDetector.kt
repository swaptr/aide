package com.sabreware.aide.platform.android.surface.ime.widget

import android.view.ViewGroup

/**
 * Port of AOSP LatinIME `KeyDetector`. Maps a touch (x, y) in host coordinates to a [Key].
 *
 * Reproduces LatinIME's two no-ghost guarantees:
 *  - **Tiling hit-boxes.** Each key's box is grown by its own layout margin, so the inter-key /
 *    inter-row gap belongs to a key — LatinIME's `hitBox = width+gap × height+gap`. The visible
 *    margin is cosmetic; the touch surface has no dead pixels. Outermost boxes are clamped to the
 *    host bounds (LatinIME's enlarged edge keys).
 *  - **Nearest-centre resolution.** `detectHitKey` returns the nearest key centre among the boxes
 *    containing the point, then falls back to the globally nearest centre, so any touch on the
 *    keyboard always resolves to a key (never a dropped DOWN).
 */
class KeyDetector {

    private val entries = ArrayList<Entry>()
    private var hysteresisSq = 0f

    /** A key's hit-cell (grown, host coords) plus its visual rect (for hysteresis edge distance). */
    class Entry(
        val key: Key,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        private val vLeft: Int,
        private val vTop: Int,
        private val vRight: Int,
        private val vBottom: Int,
    ) {
        val cx: Float = (left + right) / 2f
        val cy: Float = (top + bottom) / 2f

        fun contains(x: Int, y: Int): Boolean =
            x >= left && x < right && y >= top && y < bottom

        fun distSqToCenter(x: Int, y: Int): Float {
            val dx = x - cx
            val dy = y - cy
            return dx * dx + dy * dy
        }

        /** 0 while the point is inside the visual rect; squared distance to it once outside. */
        fun distSqToEdge(x: Int, y: Int): Float {
            val ex = x.coerceIn(vLeft, vRight)
            val ey = y.coerceIn(vTop, vBottom)
            val dx = (x - ex).toFloat()
            val dy = (y - ey).toFloat()
            return dx * dx + dy * dy
        }
    }

    /** Snapshot every Key child of [host] into tiling hit-cells. Spacers / non-keys are skipped. */
    fun setKeyboard(host: ViewGroup, hysteresisPx: Float) {
        entries.clear()
        hysteresisSq = hysteresisPx * hysteresisPx
        val w = host.width
        val h = host.height
        for (i in 0 until host.childCount) {
            val row = host.getChildAt(i) as? ViewGroup ?: continue
            val rowLeft = row.left
            val rowTop = row.top
            for (j in 0 until row.childCount) {
                val c = row.getChildAt(j)
                val key = keyOf(c) ?: continue
                val lp = c.layoutParams as? ViewGroup.MarginLayoutParams
                val mL = lp?.leftMargin ?: 0
                val mT = lp?.topMargin ?: 0
                val mR = lp?.rightMargin ?: 0
                val mB = lp?.bottomMargin ?: 0
                val vL = rowLeft + c.left
                val vT = rowTop + c.top
                val vR = rowLeft + c.right
                val vB = rowTop + c.bottom
                // Grow by the margin so adjacent cells meet at the gap mid-line (no dead zone),
                // then clamp the outermost cells out to the host edge.
                var l = vL - mL
                var t = vT - mT
                var r = vR + mR
                var b = vB + mB
                if (l <= mL) l = 0
                if (t <= mT) t = 0
                if (r >= w - mR && w > 0) r = w
                if (b >= h - mB && h > 0) b = h
                entries.add(Entry(key, l, t, r, b, vL, vT, vR, vB))
            }
        }
    }

    fun isEmpty(): Boolean = entries.isEmpty()

    /**
     * LatinIME `detectHitKey`. Cells tile the surface without overlap, so the first cell that
     * contains the point IS the key (single-pass early return). The nearest-centre value is only
     * returned as a fallback when the point lies outside every cell (sub-pixel slop past an edge).
     */
    fun detect(x: Int, y: Int): Entry? {
        var best: Entry? = null
        var min = Float.MAX_VALUE
        for (e in entries) {
            if (e.contains(x, y)) return e
            val d = e.distSqToCenter(x, y)
            if (d < min) {
                min = d
                best = e
            }
        }
        return best
    }

    fun hysteresisDistanceSq(): Float = hysteresisSq
}
