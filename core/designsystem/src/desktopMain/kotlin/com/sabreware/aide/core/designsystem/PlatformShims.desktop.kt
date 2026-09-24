package com.sabreware.aide.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sabreware.aide.core.domain.util.AideLog

@Composable
actual fun rememberHapticTick(): () -> Unit = {}

@Composable
actual fun animatorDurationScale(): Float = 1f

@Composable
actual fun rememberToaster(): (String) -> Unit = { msg -> AideLog.i("Toast", msg) }

@Composable
actual fun AppDialogWindow(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        content()
    }
}
