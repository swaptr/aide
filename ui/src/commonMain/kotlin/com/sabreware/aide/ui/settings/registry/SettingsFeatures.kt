package com.sabreware.aide.ui.settings.registry

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.sabreware.aide.core.designsystem.feature.Feature
import com.sabreware.aide.core.designsystem.feature.SettingsFeature
import com.sabreware.aide.core.designsystem.feature.SettingsFeatureRow
import com.sabreware.aide.core.designsystem.feature.SettingsSection
import com.sabreware.aide.ui.models.ModelRoute
import com.sabreware.aide.ui.models.modelDestinations
import com.sabreware.aide.ui.navigation.Route
import com.sabreware.aide.ui.settings.licenses.LicenseDetailScreen
import com.sabreware.aide.ui.settings.licenses.LicensesScreen
import com.sabreware.aide.ui.settings.mcp.ConnectorRoute
import com.sabreware.aide.ui.settings.mcp.connectorDestinations
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
    override fun register(builder: NavGraphBuilder, nav: NavHostController) {
        builder.modelDestinations()
    }
}

object ToolsFeature : SettingsFeature {
    override val section = SettingsSection.Assistant
    override val order = 10
    override val row = SettingsFeatureRow("Tools", "What the assistant can do, and when it asks.")
    override val route: Any = Route.ToolsSettings
    override fun register(builder: NavGraphBuilder, nav: NavHostController) {
        builder.composable<Route.ToolsSettings> {
            ToolsSettingsScreen()
        }
    }
}

object VoiceFeature : SettingsFeature {
    override val section = SettingsSection.Assistant
    override val order = 20
    override val row = SettingsFeatureRow("Voice", "Voice engine and chat mic.")
    override val route: Any = Route.SpeechSettings
    override fun register(builder: NavGraphBuilder, nav: NavHostController) {
        builder.composable<Route.SpeechSettings> {
            SpeechSettingsScreen()
        }
    }
}

object ConnectorsFeature : SettingsFeature {
    override val section = SettingsSection.Connectors
    override val order = 0
    override val row = SettingsFeatureRow("Connectors", "Tools from external services.")
    override val route: Any = ConnectorRoute.Home
    override fun register(builder: NavGraphBuilder, nav: NavHostController) {
        builder.connectorDestinations()
    }
}

object LicensesFeature : SettingsFeature {
    override val section = SettingsSection.About
    override val order = 0
    override val row = SettingsFeatureRow("Open source licenses", "Libraries that make Aide possible.")
    override val route: Any = Route.Licenses
    override fun register(builder: NavGraphBuilder, nav: NavHostController) {
        builder.composable<Route.Licenses> {
            LicensesScreen(
                onOpenLicense = { libraryId -> nav.navigate(Route.LicenseDetail(libraryId)) },
            )
        }
        builder.composable<Route.LicenseDetail> { entry ->
            val route = entry.toRoute<Route.LicenseDetail>()
            LicenseDetailScreen(
                libraryId = route.libraryId,
            )
        }
    }
}
