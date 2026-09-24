package com.sabreware.aide.platform.android.surface.ime.host

import android.content.Context
import android.view.View
import com.sabreware.aide.platform.android.surface.ime.theme.dp

internal class ImeHeightController(private val context: Context) {

    private var rootView: View? = null

    private var pageHeightDp: Int = 0
    private var barHeightDp: Int = 0
    private var extraHeightDp: Int = 0
    private var bottomInsetPx: Int = 0

    fun attach(root: View) {
        rootView = root
        apply(force = true)
    }

    fun detach() {
        rootView = null
    }

    fun setPageHeight(dp: Int) {
        if (pageHeightDp == dp) return
        pageHeightDp = dp
        apply()
    }

    fun setBarHeight(dp: Int) {
        if (barHeightDp == dp) return
        barHeightDp = dp
        apply()
    }

    fun setExtraHeight(dp: Int) {
        if (extraHeightDp == dp) return
        extraHeightDp = dp
        apply()
    }

    fun setBottomInset(px: Int) {
        if (bottomInsetPx == px) return
        bottomInsetPx = px
        apply()
    }

    private fun apply(force: Boolean = false) {
        val root = rootView ?: return
        val target = context.dp(
            (pageHeightDp + extraHeightDp + barHeightDp).toFloat(),
        ) + bottomInsetPx
        if (force || root.layoutParams.height != target) {
            root.layoutParams = root.layoutParams.apply { height = target }
            root.requestLayout()
        }
    }
}
