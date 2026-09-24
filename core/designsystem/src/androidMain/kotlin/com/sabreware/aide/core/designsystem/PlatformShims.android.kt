package com.sabreware.aide.core.designsystem

import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

@Composable
actual fun rememberHapticTick(): () -> Unit {
    val view = LocalView.current
    return { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
}

@Composable
actual fun animatorDurationScale(): Float {
    val context = LocalContext.current
    return Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
}

@Composable
actual fun rememberToaster(): (String) -> Unit {
    val context = LocalContext.current
    return { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
}

@Composable
actual fun AppDialogWindow(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        // Full-screen window with platform dim off (AppDialog draws its own scrim) and resize-for-keyboard.
        val view = LocalView.current
        LaunchedEffect(view) { (view.parent as? DialogWindowProvider)?.window?.applyDialogWindow() }
        content()
    }
}

private fun android.view.Window.applyDialogWindow() {
    setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    setDimAmount(0f) // AppDialog draws its own scrim
    // Edge-to-edge so the keyboard reports as a WindowInsets.ime inset (handled in content) rather
    // than the deprecated softInputMode resize.
    WindowCompat.setDecorFitsSystemWindows(this, false)
}
