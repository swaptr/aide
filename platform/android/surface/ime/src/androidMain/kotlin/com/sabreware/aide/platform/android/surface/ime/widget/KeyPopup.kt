package com.sabreware.aide.platform.android.surface.ime.widget

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.PopupWindow
import com.sabreware.aide.platform.android.surface.ime.theme.Elevation
import com.sabreware.aide.platform.android.surface.ime.theme.dp

// Slide-to-select numbers in KeyPopupTuning are gesture-feel constants, not visual tokens.
class KeyPopup(private val ctx: Context) {

    private val window = PopupWindow(ctx).apply {
        isClippingEnabled = false
        isFocusable = false
        isTouchable = false
        isOutsideTouchable = false
        setBackgroundDrawable(ColorDrawable(0))
        elevation = ctx.dp(Elevation.keyPopup).toFloat()
    }

    private val view = KeyPopupView(ctx)
    private var popupScreenLeft = 0f
    private var popupScreenTop = 0f
    private var popupAnchorScreenBottom = 0f
    private var dismissedBySlide = false

    fun isShowing(): Boolean = window.isShowing
    fun wasDismissedBySlide(): Boolean = dismissedBySlide

    fun show(anchor: View, options: List<String>) {
        if (options.isEmpty()) return
        if (window.isShowing) window.dismiss()
        dismissedBySlide = false

        view.show(options, selectedIndex = 0)

        // Measure under UNSPECIFIED so the view reports its natural grid size.
        val widthSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(widthSpec, heightSpec)
        val totalW = view.measuredWidth
        val totalH = view.measuredHeight

        (view.parent as? android.view.ViewGroup)?.removeView(view)
        window.contentView = view
        window.width = totalW
        window.height = totalH

        // showAtLocation expects window-space coords (IME window sits at the
        // bottom of the screen); use getLocationInWindow for that.
        val winLoc = IntArray(2)
        anchor.getLocationInWindow(winLoc)
        // Hit-test against ev.rawX/Y which is screen-space, so cache the
        // popup's screen rect from getLocationOnScreen.
        val scrLoc = IntArray(2)
        anchor.getLocationOnScreen(scrLoc)

        val screenW = ctx.resources.displayMetrics.widthPixels
        val anchorWinCx = winLoc[0] + anchor.width / 2
        val anchorScrCx = scrLoc[0] + anchor.width / 2

        var winX = anchorWinCx - totalW / 2
        winX = winX.coerceIn(0, (screenW - totalW).coerceAtLeast(0))
        val winY = winLoc[1] - totalH

        var scrLeft = anchorScrCx - totalW / 2
        scrLeft = scrLeft.coerceIn(0, (screenW - totalW).coerceAtLeast(0))
        popupScreenLeft = scrLeft.toFloat()
        popupScreenTop = (scrLoc[1] - totalH).toFloat()
        popupAnchorScreenBottom = scrLoc[1].toFloat()

        try {
            window.showAtLocation(anchor.rootView, Gravity.NO_GRAVITY, winX, winY)
        } catch (_: Throwable) {
            // Anchor not attached to a window yet; nothing to render.
        }
    }

    fun updateTouch(rawX: Float, rawY: Float) {
        if (!window.isShowing) return
        val totalW = view.popupWidth
        val totalH = view.popupHeight
        val cellW = view.cellWidth
        val cellH = view.cellHeight
        val cols = view.cols
        val rows = view.rows
        if (cols == 0 || rows == 0) return

        val dismissMargin = cellH * KeyPopupTuning.DISMISS_MARGIN_FACTOR
        if (rawY > popupAnchorScreenBottom + dismissMargin ||
            rawY < popupScreenTop - dismissMargin ||
            rawX < popupScreenLeft - dismissMargin ||
            rawX > popupScreenLeft + totalW + dismissMargin
        ) {
            dismissedBySlide = true
            dismiss()
            return
        }

        val relX = rawX - popupScreenLeft
        val rawRelY = rawY - popupScreenTop
        val popupCenterY = totalH / 2f
        // Finger sits on the keyboard below the popup — amplify upward motion
        // so a small lift switches to the upper row cleanly.
        val relY = popupCenterY + (rawRelY - popupCenterY) / KeyPopupTuning.VERTICAL_AMPLIFY

        val col = (relX / cellW).toInt().coerceIn(0, cols - 1)
        val viewRow = (relY / cellH).toInt().coerceIn(0, rows - 1)
        val idx = view.gridToIndex(viewRow, col)
        view.updateSelection(idx)
    }

    fun selected(): String? = view.options.getOrNull(view.selectedIndex)

    fun dismiss() {
        if (window.isShowing) window.dismiss()
        view.hide()
    }

}

/** Touch-feel constants for slide-to-alt. Ported from the reference KeyboardView. */
private object KeyPopupTuning {
    /** Dividing factor on vertical finger motion — higher = easier row switch. */
    const val VERTICAL_AMPLIFY: Float = 1.8f

    /** Slack outside the popup rect (× cell height) before sliding off dismisses. */
    const val DISMISS_MARGIN_FACTOR: Float = 1.5f
}
