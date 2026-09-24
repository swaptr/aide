package com.sabreware.aide.core.designsystem

import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

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
