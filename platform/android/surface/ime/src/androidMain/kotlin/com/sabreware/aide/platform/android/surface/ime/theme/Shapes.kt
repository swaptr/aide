package com.sabreware.aide.platform.android.surface.ime.theme

import com.sabreware.aide.platform.android.surface.ime.widget.AideButtonShape

internal object Shapes {
    const val keyCorner: Float = 8f
    const val chromeCorner: Float = 8f
    const val cardCorner: Float = 10f
    const val panelCorner: Float = 12f
    const val popupCorner: Float = 20f

    val key: AideButtonShape = AideButtonShape.Rounded(keyCorner)
    val chrome: AideButtonShape = AideButtonShape.Rounded(chromeCorner)
    val pill: AideButtonShape = AideButtonShape.Pill
}
