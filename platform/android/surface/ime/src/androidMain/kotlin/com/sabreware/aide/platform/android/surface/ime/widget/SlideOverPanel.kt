package com.sabreware.aide.platform.android.surface.ime.widget

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.sabreware.aide.platform.android.surface.ime.theme.Motion
import com.sabreware.aide.platform.android.surface.ime.theme.Sizes
import com.sabreware.aide.platform.android.surface.ime.theme.dp

// DrawerLayout requires fitsSystemWindows insets handling the IME doesn't get; this rolls our own.
enum class PanelSide { Start, End }

class SlideOverPanel(
    context: Context,
    private val panelWidthDp: Float = Sizes.slidePanelWidth,
    private val animationDurationMs: Long = Motion.slidePanel,
    private val side: PanelSide = PanelSide.Start,
) {

    val root: FrameLayout = FrameLayout(context)

    private val panelWidthPx: Int = context.dp(panelWidthDp)

    private val closedPanelTx: Float = when (side) {
        PanelSide.Start -> -panelWidthPx.toFloat()
        PanelSide.End -> panelWidthPx.toFloat()
    }
    private val openContentTx: Float = when (side) {
        PanelSide.Start -> panelWidthPx.toFloat()
        PanelSide.End -> -panelWidthPx.toFloat()
    }

    private val contentSlot: FrameLayout = FrameLayout(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
    }

    private val panelSlot: FrameLayout = FrameLayout(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            panelWidthPx,
            FrameLayout.LayoutParams.MATCH_PARENT,
            when (side) {
                PanelSide.Start -> Gravity.START
                PanelSide.End -> Gravity.END
            },
        )
        translationX = closedPanelTx
    }

    @Volatile var isOpen: Boolean = false
        private set

    init {
        // Content first so it sits underneath; panel rendered on top.
        root.addView(contentSlot)
        root.addView(panelSlot)
    }

    fun setContent(view: View) {
        contentSlot.removeAllViews()
        contentSlot.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    fun setPanel(view: View) {
        panelSlot.removeAllViews()
        panelSlot.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    fun open() {
        if (isOpen) return
        isOpen = true
        animateTo(panelTarget = 0f, contentTarget = openContentTx)
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        animateTo(panelTarget = closedPanelTx, contentTarget = 0f)
    }

    fun toggle() {
        if (isOpen) close() else open()
    }

    private fun animateTo(panelTarget: Float, contentTarget: Float) {
        panelSlot.animate()
            .translationX(panelTarget)
            .setDuration(animationDurationMs)
            .start()
        contentSlot.animate()
            .translationX(contentTarget)
            .setDuration(animationDurationMs)
            .start()
    }
}
