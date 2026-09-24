package com.sabreware.aide.ui.models

import com.sabreware.aide.ui.labels.TagsPage
import com.sabreware.aide.ui.models.connections.ConnectPage
import com.sabreware.aide.ui.models.connections.ConnectionPage
import com.sabreware.aide.ui.models.connections.ConnectionsPage
import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import com.sabreware.aide.core.designsystem.AppDialog
import com.sabreware.aide.core.designsystem.AppDialogSize
import com.sabreware.aide.core.designsystem.navigation.navigator
import com.sabreware.aide.core.designsystem.rememberNavDialogBackStack
import com.sabreware.aide.ui.navigation.page

/**
 * The model flow's host wiring. The same [ModelPages] are registered ONCE per host — as full-screen NavHost
 * destinations ([modelDestinations], called from the app graph, reached via Settings) and as headerless sheet
 * pages ([ModelDialog], opened from the chat model pill). The page composables are the single source.
 */
fun NavGraphBuilder.modelDestinations() {
    page<ModelRoute.Home> { ModelHomePage() }
    page<ModelRoute.AddPick> { AddModelPickPage() }
    page<ModelRoute.AddModels> { AddModelModelsPage(ModalityGroup.valueOf(it.modality)) }
    page<ModelRoute.Connections> { ConnectionsPage() }
    page<ModelRoute.Connection> { ConnectionPage(it.id) }
    page<ModelRoute.Connect> { ConnectPage(it.service, it.editId.takeIf(String::isNotEmpty)) }
    page<ModelRoute.Browse> { ModelHomePage(filter = it.facet to it.option) }
    page<ModelRoute.Tags> { AutoTagsAwareTagsPage() }
}

/**
 * The model select/add flow as a sheet (chat): the same pages, hosted headerless. Expandable (60% peek ↔
 * 100%). [start] lets a caller jump straight into a step (e.g. [ModelRoute.AddPick]); dismiss closes it.
 */
@Composable
fun ModelDialog(onDismiss: () -> Unit, start: ModelRoute = ModelRoute.Home) {
    val backStack = rememberNavDialogBackStack<ModelRoute>(start)
    AppDialog(backStack = backStack, onDismiss = onDismiss, size = AppDialogSize.Expandable) {
        page<ModelRoute.Home> { _, _ -> ModelHomePage() }
        page<ModelRoute.AddPick> { _, _ -> AddModelPickPage() }
        page<ModelRoute.AddModels> { route, _ -> AddModelModelsPage(ModalityGroup.valueOf(route.modality)) }
        page<ModelRoute.Connections> { _, _ -> ConnectionsPage() }
        page<ModelRoute.Connection> { route, _ -> ConnectionPage(route.id) }
        page<ModelRoute.Connect> { route, _ -> ConnectPage(route.service, route.editId.takeIf(String::isNotEmpty)) }
        page<ModelRoute.Browse> { route, _ -> ModelHomePage(filter = route.facet to route.option) }
        page<ModelRoute.Tags> { _, _ -> AutoTagsAwareTagsPage() }
    }
}

/** The Tags page with the models' automatic tags; tapping one opens Models filtered by it. */
@Composable
private fun AutoTagsAwareTagsPage() {
    val nav = navigator()
    TagsPage(rememberModelAutoTags(), onOpenAutomatic = { nav.navigate(ModelRoute.Browse(it.facet, it.option)) })
}
