package com.sabreware.aide.ui.settings.registry

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.sabreware.aide.core.designsystem.navigation.navigator
import com.sabreware.aide.ui.models.modelEntries
import com.sabreware.aide.ui.settings.mcp.connectorEntries
import com.sabreware.aide.core.designsystem.feature.Feature
import com.sabreware.aide.core.designsystem.feature.SettingsFeature
import com.sabreware.aide.core.designsystem.feature.SettingsFeatureRow
import com.sabreware.aide.core.designsystem.feature.SettingsSection
import com.sabreware.aide.ui.models.ModelRoute
import com.sabreware.aide.ui.navigation.Route
import com.sabreware.aide.ui.settings.licenses.LicenseDetailScreen
import com.sabreware.aide.ui.settings.licenses.LicensesScreen
import com.sabreware.aide.ui.settings.mcp.ConnectorRoute
import com.sabreware.aide.ui.settings.speech.SpeechSettingsScreen
import com.sabreware.aide.ui.settings.tools.ToolsSettingsScreen

/**
 * The navigable features every platform ships. An application composes its own set from this plus whatever
 * its platform contributes — `:app` adds the Android-only surfaces, `:desktopApp` adds nothing — and installs
 * it with `featureModules(...)`. Nothing here knows which platform it is running on.
 */
val commonFeatures: List<Feature> = listOf(
    ModelsFeature,
    ToolsFeature,
    VoiceFeature,
    ConnectorsFeature,
    LicensesFeature,
)


// --- Shared feature objects (each wraps its existing row text + route + destination wiring) ---

/** Also hosts Connections ([ModelRoute.Connections]): the models flow's own pages, reached from its rail. */
object ModelsFeature : SettingsFeature {
    override val section = SettingsSection.Assistant
    override val order = 0
    override val row = SettingsFeatureRow("Models", "Chat and voice models, and connections.")
    override val route: Any = ModelRoute.Home
    override fun EntryProviderScope<NavKey>.entries() = modelEntries()
}

object ToolsFeature : SettingsFeature {
    override val section = SettingsSection.Assistant
    override val order = 10
    override val row = SettingsFeatureRow("Tools", "What the assistant can do, and when it asks.")
    override val route: Any = Route.ToolsSettings
    override fun EntryProviderScope<NavKey>.entries() {
        entry<Route.ToolsSettings> { ToolsSettingsScreen() }
    }
}

object VoiceFeature : SettingsFeature {
    override val section = SettingsSection.Assistant
    override val order = 20
    override val row = SettingsFeatureRow("Voice", "Voice engine and chat mic.")
    override val route: Any = Route.SpeechSettings
    override fun EntryProviderScope<NavKey>.entries() {
        entry<Route.SpeechSettings> { SpeechSettingsScreen() }
    }
}

object ConnectorsFeature : SettingsFeature {
    override val section = SettingsSection.Connectors
    override val order = 0
    override val row = SettingsFeatureRow("Connectors", "Tools from external services.")
    override val route: Any = ConnectorRoute.Home
    override fun EntryProviderScope<NavKey>.entries() = connectorEntries()
}

object LicensesFeature : SettingsFeature {
    override val section = SettingsSection.About
    override val order = 0
    override val row = SettingsFeatureRow("Open source licenses", "Libraries that make Aide possible.")
    override val route: Any = Route.Licenses
    override fun EntryProviderScope<NavKey>.entries() {
        entry<Route.Licenses> {
            val nav = navigator()
            LicensesScreen(onOpenLicense = { libraryId -> nav.navigate(Route.LicenseDetail(libraryId)) })
        }
        entry<Route.LicenseDetail> { LicenseDetailScreen(libraryId = it.libraryId) }
    }
}
