package com.swaptr.aide.ime.host

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.swaptr.aide.R
import com.swaptr.aide.ime.theme.Sizes
import com.swaptr.aide.ime.theme.dp
import kotlin.math.max

internal class ImeWindowChrome(
    private val ctx: Context,
    private val popup: View,
    private val bar: View,
    private val pageSlot: View,
    private val heightCtrl: ImeHeightController,
) {

    fun build(): LinearLayout {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // ImeHeightController owns the final height; init to 0 + match-parent
            // width and let the first `apply()` set the real value.
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                0,
            )
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.aide_surface))
            addView(
                popup,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0),
            )
            addView(
                bar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    ctx.dp(Sizes.barHeight),
                ),
            )
            addView(
                pageSlot,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        heightCtrl.attach(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val sysGestures = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
            val bottom = max(navBars.bottom, sysGestures.bottom)
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottom)
            heightCtrl.setBottomInset(bottom)
            insets
        }
        return root
    }
}
