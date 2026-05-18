package com.swaptr.aide.ime.widget

import android.content.Context
import android.util.TypedValue

// Legacy helper for AideButton only (predates ime/theme); other IME widgets use theme.dp.
internal fun Context.dpToPx(value: Float): Int =
    TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        resources.displayMetrics,
    ).toInt()
