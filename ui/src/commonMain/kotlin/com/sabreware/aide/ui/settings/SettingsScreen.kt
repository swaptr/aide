package com.sabreware.aide.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sabreware.aide.core.designsystem.AppMenu
import com.sabreware.aide.core.designsystem.AppMenuEntry
import com.sabreware.aide.core.designsystem.AppPage
import com.sabreware.aide.core.designsystem.HeaderAction
import com.sabreware.aide.core.designsystem.feature.FeatureRegistry
import com.sabreware.aide.core.designsystem.feature.SettingsSection
import com.sabreware.aide.ui.settings.fonts.ChatFontStyleSheet
import com.sabreware.aide.ui.settings.fonts.ChatTextSize
import com.sabreware.aide.ui.settings.fonts.ChatTextSizeSheet
import com.sabreware.aide.ui.settings.fonts.chatFontStyleLabel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * The Settings menu. Its navigable rows are **not** hardcoded — they are the [FeatureRegistry] this
 * application installed, rendered one [AppMenu] per [SettingsSection]. Platform-only features (Keyboard,
 * Digital Assistant) appear only where their platform contributed them; this screen never branches on
 * platform.
 * [onOpenFeature] navigates to a tapped feature's `route`.
 *
 * The **inline preference** rows (Color theme, Text size, Font style, Model fallback) are not navigable
 * features — they flip a local value through a sheet — so they stay declared here, slotted into their
 * section's list.
 */
@Composable
fun SettingsScreen(
    onOpenDrawer: () -> Unit,
    onOpenFeature: (route: Any) -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showAppearance by rememberSaveable { mutableStateOf(false) }
    var showTextSize by rememberSaveable { mutableStateOf(false) }
    var showFontStyle by rememberSaveable { mutableStateOf(false) }
    var showFallback by rememberSaveable { mutableStateOf(false) }
    // Resolved once, outside the per-section buildList (which is not a composable context).
    val settingsFeatures = koinInject<FeatureRegistry>().settingsFeatures
    AppPage(
        title = "Settings",
        leadingAction = HeaderAction.drawer(onOpenDrawer),
    ) {
        SettingsSection.entries.forEach { section ->
            val items = buildList {
                if (section == SettingsSection.Appearance) {
                    add(
                        AppMenuEntry(
                            title = "Color theme",
                            subtitle = themeModeLabel(uiState.themeMode),
                            onClick = { showAppearance = true },
                        ),
                    )
                    add(
                        AppMenuEntry(
                            title = "Text size",
                            subtitle = ChatTextSize.nearest(uiState.fontScale).label,
                            onClick = { showTextSize = true },
                        ),
                    )
                    add(
                        AppMenuEntry(
                            title = "Font style",
                            subtitle = chatFontStyleLabel(uiState.chatFontStyle),
                            onClick = { showFontStyle = true },
                        ),
                    )
                }
                settingsFeatures
                    .filter { it.section == section }
                    .sortedBy { it.order }
                    .forEach { feature ->
                        add(
                            AppMenuEntry(
                                title = feature.row.title,
                                subtitle = feature.row.subtitle,
                                leadingIconRes = feature.row.leadingIconRes,
                                onClick = { onOpenFeature(feature.route) },
                            ),
                        )
                    }
                if (section == SettingsSection.Assistant) {
                    add(
                        AppMenuEntry(
                            title = "Model fallback",
                            subtitle = modelFallbackLabel(uiState.modelFallback),
                            onClick = { showFallback = true },
                        ),
                    )
                }
            }
            if (items.isNotEmpty()) {
                AppMenu(title = section.title, items = items)
            }
        }
        if (showAppearance) {
            AppearanceSheet(
                selected = uiState.themeMode,
                onSelect = viewModel::setThemeMode,
                onDismiss = { showAppearance = false },
            )
        }
        if (showTextSize) {
            ChatTextSizeSheet(
                selected = ChatTextSize.nearest(uiState.fontScale),
                onSelect = { viewModel.setFontScale(it.scale) },
                onDismiss = { showTextSize = false },
            )
        }
        if (showFontStyle) {
            ChatFontStyleSheet(
                selected = uiState.chatFontStyle,
                onSelect = viewModel::setChatFontStyle,
                onDismiss = { showFontStyle = false },
            )
        }
        if (showFallback) {
            ModelFallbackSheet(
                selected = uiState.modelFallback,
                onSelect = viewModel::setModelFallback,
                onDismiss = { showFallback = false },
            )
        }
    }
}
