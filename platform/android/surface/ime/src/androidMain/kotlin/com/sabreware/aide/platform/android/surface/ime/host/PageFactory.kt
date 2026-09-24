package com.sabreware.aide.platform.android.surface.ime.host

import android.content.Context
import com.sabreware.aide.platform.android.surface.ime.prefs.ImePage
import com.sabreware.aide.platform.android.surface.ime.page.IMEPage
import com.sabreware.aide.platform.android.surface.ime.page.KeyboardPage
import com.sabreware.aide.platform.android.surface.ime.transform.TransformController

internal class PageFactory(
    private val ctx: Context,
    private val controller: TransformController,
) {
    fun build(page: ImePage): IMEPage = when (page) {
        ImePage.KEYBOARD -> KeyboardPage(
            context = ctx,
            controller = controller,
        )
    }
}
