package com.sabreware.aide.platform.android.surface.ime.data

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.sabreware.aide.platform.android.surface.ime.domain.AideKeyboardManager
import com.sabreware.aide.platform.android.surface.ime.domain.AideKeyboardStatus

/**
 * [AideKeyboardManager] over the platform IME APIs. Aide ships exactly one input method, so the app's
 * package is its identity — match by package, never by the flattened id (OEMs report it in short form
 * `pkg/.Service` vs. long form `pkg/pkg.Service`). Enabled = an [InputMethodManager.getEnabledInputMethodList]
 * entry from our package. Active = the selected IME's package is ours: on API 34+ via the structured
 * [InputMethodManager.getCurrentInputMethodInfo], and below that via [Settings.Secure.DEFAULT_INPUT_METHOD]
 * (which stores `InputMethodInfo#getId()`). All reads are permission-free and self-package is always
 * visible (so the API 30+ package-visibility filtering doesn't hide us). Mirrors AnySoftKeyboard's check.
 */
class AndroidAideKeyboardManager(
    private val context: Context,
) : AideKeyboardManager {

    private val inputMethodManager: InputMethodManager?
        get() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    override fun currentStatus(): AideKeyboardStatus {
        val imm = inputMethodManager ?: return AideKeyboardStatus.NotEnabled
        val pkg = context.packageName
        if (imm.enabledInputMethodList.none { it.packageName == pkg }) return AideKeyboardStatus.NotEnabled
        return if (isActiveIme(imm, pkg)) AideKeyboardStatus.Active else AideKeyboardStatus.EnabledInactive
    }

    private fun isActiveIme(imm: InputMethodManager, pkg: String): Boolean =
        imm.currentInputMethodInfo?.packageName == pkg

    override fun openEnableSettings() {
        // NEW_TASK: launched from the application (non-Activity) context. runCatching guards the rare
        // OEM that doesn't expose the input-method settings screen.
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    override fun openSwitcher() {
        inputMethodManager?.showInputMethodPicker()
    }
}
