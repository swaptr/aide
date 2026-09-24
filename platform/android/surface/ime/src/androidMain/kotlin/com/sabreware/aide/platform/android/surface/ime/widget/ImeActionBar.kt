package com.sabreware.aide.platform.android.surface.ime.widget

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.sabreware.aide.platform.android.surface.ime.theme.Sizes
import com.sabreware.aide.platform.android.surface.ime.theme.Spacing
import com.sabreware.aide.platform.android.surface.ime.theme.dp

class ImeActionBar(
    context: Context,
    heightPx: Int = context.dp(Sizes.barHeight),
    // false when caller hands its own scroller via setMiddle (nesting scrolls breaks flings).
    scrollableMiddle: Boolean = true,
) : FrameLayout(context) {

    private val ctx: Context = context
    private val gapPx: Int = ctx.dp(Spacing.rowGap)
    private val edgePadPx: Int = ctx.dp(Spacing.barEdge)

    private val leadingSlot: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val middleSlot: FrameLayout = FrameLayout(ctx)
    private val trailingSlot: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    // Always present; detaches if setMiddle swaps in a custom view, reattaches later.
    val scrollContent: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
    }

    init {
        // Inline parenting avoids the double-parent crash that comes from class-level val init.
        val initialMiddle: View = if (scrollableMiddle) {
            HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                clipChildren = false
                clipToPadding = false
                addView(scrollContent)
            }
        } else {
            scrollContent
        }
        middleSlot.addView(
            initialMiddle,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                leadingSlot,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                middleSlot,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
            )
            addView(
                trailingSlot,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        // Edge padding on outer FrameLayout survives host layoutParams overrides.
        // vPad = (bar-btn)/2 makes default button vertically fill the row exactly.
        val buttonSizePx = ctx.dp(Sizes.buttonSize)
        val vPadPx = ((heightPx - buttonSizePx) / 2).coerceAtLeast(0)
        setPadding(edgePadPx, vPadPx, edgePadPx, vPadPx)
        clipToPadding = false
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, heightPx)
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setLeading(view: View?) {
        setLeadingChildren(if (view == null) emptyList() else listOf(view))
    }

    fun setTrailing(view: View?) {
        setTrailingChildren(if (view == null) emptyList() else listOf(view))
    }

    fun setLeadingChildren(views: List<View>) {
        fillRow(leadingSlot, views, leading = true)
    }

    fun setTrailingChildren(views: List<View>) {
        fillRow(trailingSlot, views, leading = false)
    }

    fun setMiddle(view: View) {
        if (middleSlot.childCount == 1 && middleSlot.getChildAt(0) === view) return
        middleSlot.removeAllViews()
        (view.parent as? ViewGroup)?.removeView(view)
        middleSlot.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    fun addToScroll(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
        val lp = (view.layoutParams as? MarginLayoutParams)
            ?: MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        lp.marginEnd = gapPx
        scrollContent.addView(view, lp)
    }

    fun setScrollChildren(views: List<View>) {
        scrollContent.removeAllViews()
        views.forEach { addToScroll(it) }
    }

    fun clearScroll() {
        scrollContent.removeAllViews()
    }

    private fun fillRow(slot: LinearLayout, views: List<View>, leading: Boolean) {
        slot.removeAllViews()
        if (views.isEmpty()) return
        views.forEachIndexed { index, view ->
            (view.parent as? ViewGroup)?.removeView(view)
            // Preserve aideButton's intrinsic size; WRAP_CONTENT shrinks chrome to icon footprint.
            val existing = view.layoutParams as? MarginLayoutParams
            val lp = if (existing != null) {
                LinearLayout.LayoutParams(existing.width, existing.height)
            } else {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            if (leading) {
                lp.marginStart = if (index == 0) 0 else gapPx
                if (index == views.lastIndex) lp.marginEnd = gapPx
            } else {
                lp.marginStart = gapPx
                lp.marginEnd = 0
            }
            slot.addView(view, lp)
        }
    }
}
