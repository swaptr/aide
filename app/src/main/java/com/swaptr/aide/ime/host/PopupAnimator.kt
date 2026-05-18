package com.swaptr.aide.ime.host

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import com.swaptr.aide.ime.theme.Motion
import com.swaptr.aide.ime.theme.Sizes
import com.swaptr.aide.ime.theme.Spacing
import com.swaptr.aide.ime.theme.dp
import com.swaptr.aide.ime.widget.ResponsePopupView

internal class PopupAnimator(
    private val ctx: Context,
    private val popup: ResponsePopupView,
    private val heightCtrl: ImeHeightController,
) {

    private var shown: Boolean = false

    fun setVisible(visible: Boolean) {
        if (visible == shown && popup.visibility == (if (visible) View.VISIBLE else View.GONE)) return
        if (visible) show() else hide()
    }

    /** Cancel the popup's flow observer. Service calls before `serviceScope.cancel()`. */
    fun stop() {
        popup.stop()
        popup.animate().cancel()
    }

    private fun show() {
        shown = true
        val lp = popup.layoutParams as LinearLayout.LayoutParams
        lp.height = ctx.dp(Sizes.popupHeight)
        popup.layoutParams = lp
        heightCtrl.setExtraHeight(Sizes.popupHeight.toInt())
        popup.visibility = View.VISIBLE
        popup.translationY = ctx.dp(Spacing.lg).toFloat()
        popup.alpha = 0f
        popup.animate().cancel()
        popup.animate()
            .alpha(1f)
            .translationY(0f)
            .setInterpolator(Motion.fastOutSlowIn())
            .setDuration(Motion.popupFadeIn)
            .start()
    }

    private fun hide() {
        shown = false
        popup.animate().cancel()
        popup.animate()
            .alpha(0f)
            .translationY(ctx.dp(Spacing.lg).toFloat())
            .setInterpolator(Motion.fastOutLinearIn())
            .setDuration(Motion.popupFadeOut)
            .withEndAction {
                val lp = popup.layoutParams as LinearLayout.LayoutParams
                lp.height = 0
                popup.layoutParams = lp
                popup.visibility = View.GONE
                heightCtrl.setExtraHeight(0)
            }
            .start()
    }
}
