package com.sabreware.aide.core.designsystem

import androidx.compose.runtime.Composable
import com.sabreware.aide.core.domain.util.AideLog

@Composable
actual fun rememberHapticTick(): () -> Unit = {}

@Composable
actual fun animatorDurationScale(): Float = 1f

@Composable
actual fun rememberToaster(): (String) -> Unit = { msg -> AideLog.i("Toast", msg) }
