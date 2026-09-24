package com.sabreware.aide.ui.navigation

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import com.sabreware.aide.core.designsystem.feature.FeatureRegistry
import com.sabreware.aide.core.designsystem.navigation.InModal
import com.sabreware.aide.ui.models.ModelRoute
import com.sabreware.aide.ui.settings.mcp.ConnectorRoute
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclassesOfSealed

/**
 * How the back stack saves: every [NavKey] type registered for open polymorphism (required off Android, where
 * nothing is found by reflection). The shared `:ui` routes are registered here; each feature adds the routes
 * it owns through [com.sabreware.aide.core.designsystem.feature.Feature.routes] — contributed, never listed.
 */
@OptIn(ExperimentalSerializationApi::class)
fun navStateConfiguration(features: FeatureRegistry): SavedStateConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclassesOfSealed<Route>()
            subclassesOfSealed<ModelRoute>()
            subclassesOfSealed<ConnectorRoute>()
            subclass(InModal::class, InModal.serializer())
            features.features.forEach { with(it) { routes() } }
        }
    }
}
