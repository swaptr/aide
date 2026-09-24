package com.sabreware.aide.ui.models

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * The model select/add flow as ONE host-agnostic route family. The same routes render as screens (pushed
 * from Settings) or as pages in a modal ([openModelFlow], from the chat pill) — see
 * [ModelPages]. Mirrors the connector flow's `ConnectorRoute`. A model itself is never a route: tapping one opens
 * the model sheet over the page ([ModelSheetHost]), in every host. Route args are plain
 * String, like every route arg); the page resolves the
 * [com.sabreware.aide.core.domain.model.ModelSpec] from the registry.
 */
@Serializable
sealed interface ModelRoute : NavKey {
    /** The user's models (pinned, in use, installed); a model opens its sheet; add goes to [AddPick]. */
    @Serializable data object Home : ModelRoute


    /** Add-model step 1 — pick a modality. */
    @Serializable data object AddPick : ModelRoute

    /**
     * Add-model step 2 — ONE page for the chosen [modality]: a tab per source (on-device, then each cloud
     * credential), each tab its action row over its models. [modality] is the [ModalityGroup] name (a plain
     * String) — route args stay primitive so no custom `NavType` is needed and the route is portable across
     * every nav host (Android/Desktop/iOS). The page resolves the enum.
     */
    @Serializable data class AddModels(val modality: String) : ModelRoute

    /**
     * Every connection, and every vendor that can be connected — the one place accounts are managed, also
     * reached from Settings.
     */
    @Serializable data object Connections : ModelRoute

    /** One connection: its status, its models and everything that can be done to it. */
    @Serializable data class Connection(val id: String) : ModelRoute

    /**
     * The connect form for [service] (a [com.sabreware.aide.core.domain.connection.ServiceDescriptor] id);
     * [editId] edits an existing connection instead.
     */
    @Serializable data class Connect(val service: String, val editId: String = "") : ModelRoute

    /**
     * The models page opened already filtered by one facet option — where an automatic tag ("Cloud", "Vision",
     * "128K+") leads. Plain Strings, like every route arg here.
     */
    @Serializable data class Browse(val facet: String, val option: String) : ModelRoute

    /** The user's tag vocabulary. */
    @Serializable data object Tags : ModelRoute
}
