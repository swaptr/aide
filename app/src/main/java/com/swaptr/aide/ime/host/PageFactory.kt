package com.swaptr.aide.ime.host

import android.content.Context
import com.swaptr.aide.data.prefs.ImePage
import com.swaptr.aide.ime.page.IMEPage
import com.swaptr.aide.ime.page.KeyboardPage
import com.swaptr.aide.ime.transform.TransformController

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
