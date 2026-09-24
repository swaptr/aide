package com.sabreware.aide.ui.app

import com.sabreware.aide.core.common.prefs.Tier
import com.sabreware.aide.core.common.prefs.boolKey
import com.sabreware.aide.core.designsystem.state.UiState

object ShellKeys {
    val SidebarOpen = boolKey("ui.sidebar_open", default = false, tier = Tier.UiState)
}
