package com.sabreware.aide.platform.android.surface.ime.widget

import android.content.Context
import android.util.TypedValue

// AideButton's dp→px helper (Float dp). Other IME widgets use the Int-based theme.dp.
internal fun Context.dpToPx(value: Float): Int =
    TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        resources.displayMetrics,
    ).toInt()
