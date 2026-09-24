package com.sabreware.aide.app.feature

import com.sabreware.aide.core.designsystem.feature.Feature
import com.sabreware.aide.core.designsystem.feature.SettingsFeature
import com.sabreware.aide.core.designsystem.feature.SettingsFeatureRow
import com.sabreware.aide.core.designsystem.feature.SettingsSection
import com.sabreware.aide.feature.tasks.ui.TasksFeature
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.sabreware.aide.core.designsystem.navigation.navigator
import com.sabreware.aide.feature.tasks.ui.TaskRoute
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.modules.PolymorphicModuleBuilder
import kotlinx.serialization.modules.subclassesOfSealed
import com.sabreware.aide.app.assistant.settings.AssistantSettingsScreen
import com.sabreware.aide.app.assistant.settings.AssistantSettingsViewModel
import com.sabreware.aide.platform.android.surface.ime.settings.KeyboardSettingsScreen
import com.sabreware.aide.platform.android.surface.ime.settings.KeyboardSettingsViewModel
import kotlinx.serialization.Serializable
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Android's platform-only features — device system-integration surfaces (registering Aide as the default
 * IME / digital assistant) and the IME-driven Tasks feature, none of which another platform implements. They
 * live in `:app` and in Android-only modules (`:feature:tasks`), so the desktop compilation never sees them
 * and neither the rows/screens nor the code (down to the tasks Room database) ship in the desktop app.
 *
 * `:app` appends this to [com.sabreware.aide.ui.settings.registry.commonFeatures] when it starts Koin. No
 * other platform can: this file, and every module it names, is Android-only.
 */
val androidFeatures: List<Feature> = listOf(
    AssistantSettingsFeature,
    KeyboardSettingsFeature,
    TasksFeature,
)

/** Route keys for the Android-only feature screens — their own sealed type (not members of the common
 *  [Route]), registered for saving by the features that own them. */
@Serializable
sealed interface AndroidSettingsRoute : NavKey {
    @Serializable data object Keyboard : AndroidSettingsRoute
    @Serializable data object Assistant : AndroidSettingsRoute
}

object AssistantSettingsFeature : SettingsFeature {
    override val section = SettingsSection.System
    override val order = 10
    override val row = SettingsFeatureRow("Digital Assistant", "Set Aide as the device default.")
    override val route: Any = AndroidSettingsRoute.Assistant
    override fun EntryProviderScope<NavKey>.entries() {
        entry<AndroidSettingsRoute.Assistant> { AssistantSettingsScreen() }
    }

    // Both Android settings routes are one sealed type; this feature registers it for both.
    @OptIn(ExperimentalSerializationApi::class)
    override fun PolymorphicModuleBuilder<NavKey>.routes() {
        subclassesOfSealed<AndroidSettingsRoute>()
    }
    override val koinModule = module { viewModelOf(::AssistantSettingsViewModel) }
}

object KeyboardSettingsFeature : SettingsFeature {
    override val section = SettingsSection.System
    override val order = 20
    override val row = SettingsFeatureRow("Keyboard", "Enable, switch, dictation.")
    override val route: Any = AndroidSettingsRoute.Keyboard
    override fun EntryProviderScope<NavKey>.entries() {
        entry<AndroidSettingsRoute.Keyboard> {
            val nav = navigator()
            KeyboardSettingsScreen(onOpenTasks = { nav.navigate(TaskRoute.Home) })
        }
    }
    override val koinModule = module { viewModelOf(::KeyboardSettingsViewModel) }
}
