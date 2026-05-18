package com.swaptr.aide.ime.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.swaptr.aide.R

class BarOverlay(rawContext: Context) : FrameLayout(rawContext) {

    private val backdrop: View = View(context).apply {
        // Match aide_surface so the bar fades into the keyboard, not a hard slab.
        val base = ContextCompat.getColor(context, R.color.aide_surface)
        setBackgroundColor(
            Color.argb(
                BACKDROP_ALPHA,
                Color.red(base),
                Color.green(base),
                Color.blue(base),
            ),
        )
    }

    private val contentSlot: FrameLayout = FrameLayout(context)

    // ColorDrawable mask keeps ripple inside the bar rect (no bleed onto chip strip/keyboard).
    private val ripple: RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(
            ContextCompat.getColor(context, R.color.aide_outline_variant),
        ),
        null,
        ColorDrawable(Color.WHITE),
    )

    init {
        addView(
            backdrop,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(
            contentSlot,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        visibility = GONE
    }

    // null onTap = non-interactive; taps fall through while backdrop still masks chrome below.
    fun show(content: View, onTap: (() -> Unit)? = null) {
        contentSlot.removeAllViews()
        (content.parent as? ViewGroup)?.removeView(content)
        contentSlot.addView(
            content,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        if (onTap != null) {
            isClickable = true
            isFocusable = true
            foreground = ripple
            setOnClickListener { onTap() }
        } else {
            isClickable = false
            isFocusable = false
            foreground = null
            setOnClickListener(null)
        }
        visibility = VISIBLE
    }

    fun hide() {
        contentSlot.removeAllViews()
        isClickable = false
        isFocusable = false
        foreground = null
        setOnClickListener(null)
        visibility = GONE
    }

    private companion object {
        // 90% opacity = chrome icons very faintly visible behind the tint;
        // 230 / 255.
        const val BACKDROP_ALPHA = 230
    }
}
