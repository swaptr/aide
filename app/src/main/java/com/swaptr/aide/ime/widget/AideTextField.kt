package com.swaptr.aide.ime.widget

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.swaptr.aide.R
import com.swaptr.aide.ime.theme.Shapes
import com.swaptr.aide.ime.theme.Sizes
import com.swaptr.aide.ime.theme.Spacing
import com.swaptr.aide.ime.theme.Typography
import com.swaptr.aide.ime.theme.dp

// ScrollView wrap for streaming preview — ScrollingMovementMethod clips fractional lines.
// Card clickable; inner views must not intercept so ripple/onClick still fires outside scroll.
class AideTextCard(context: Context) : MaterialCardView(context) {

    private val tv: TextView = TextView(context).apply {
        textSize = Typography.queueRow
        setLineSpacing(0f, 1.15f)
        gravity = Gravity.TOP or Gravity.START
        setPadding(
            context.dp(Spacing.xl), context.dp(Spacing.responsePadV),
            context.dp(Spacing.xl), context.dp(Spacing.responsePadV),
        )
        isClickable = false
        isFocusable = false
        isLongClickable = false
    }

    private var scroller: ScrollView? = null

    init {
        radius = context.dp(Shapes.cardCorner).toFloat()
        strokeWidth = context.dp(Sizes.strokeThin)
        strokeColor = ContextCompat.getColor(context, R.color.aide_outline)
        setCardBackgroundColor(ContextCompat.getColor(context, R.color.aide_surface_container))
        cardElevation = 0f
        addView(
            tv,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    var text: CharSequence
        get() = tv.text ?: ""
        set(value) {
            tv.text = value
            // post() defers scroll until new content lays out — direct scroll hits stale bounds.
            scroller?.post { scroller?.fullScroll(View.FOCUS_DOWN) }
        }

    var maxTextLines: Int
        get() = tv.maxLines
        set(value) {
            tv.maxLines = value
            tv.ellipsize = TextUtils.TruncateAt.END
        }

    fun setTextColorInt(color: Int) {
        tv.setTextColor(color)
    }

    // Drop maxLines or the scroller would still clip. Outer card height bounds the visible region.
    fun makeScrollable() {
        if (scroller != null) return
        removeAllViews()
        tv.maxLines = Int.MAX_VALUE
        tv.ellipsize = null
        val sv = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                tv,
                LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        scroller = sv
        addView(
            sv,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
            ),
        )
    }
}

fun aideTextField(
    ctx: Context,
    hint: String,
    maxLines: Int,
    scrollable: Boolean = false,
): AideTextCard = AideTextCard(ctx).apply {
    text = hint
    setTextColorInt(ContextCompat.getColor(ctx, R.color.aide_on_surface_variant))
    if (scrollable) {
        // Order matters: enable scrolling FIRST so maxTextLines doesn't get
        // applied while a stale-state TextView is still capped.
        makeScrollable()
    } else {
        maxTextLines = maxLines
    }
}
