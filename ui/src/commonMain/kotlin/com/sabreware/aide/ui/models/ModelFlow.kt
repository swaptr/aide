package com.sabreware.aide.ui.models

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.sabreware.aide.core.designsystem.navigation.Navigator
import com.sabreware.aide.core.designsystem.navigation.navigator
import com.sabreware.aide.core.designsystem.navigation.openModal
import com.sabreware.aide.ui.labels.TagsPage
import com.sabreware.aide.ui.models.connections.ConnectPage
import com.sabreware.aide.ui.models.connections.ConnectionPage
import com.sabreware.aide.ui.models.connections.ConnectionsPage

/**
 * The model flow's pages, registered ONCE. Settings pushes them as screens; the chat pill opens them in a
 * modal ([openModelFlow]) — the same entries either way, only the container differs.
 */
fun EntryProviderScope<NavKey>.modelEntries() {
    entry<ModelRoute.Home> { ModelHomePage() }
    entry<ModelRoute.AddPick> { AddModelPickPage() }
    entry<ModelRoute.AddModels> { AddModelModelsPage(ModalityGroup.valueOf(it.modality)) }
    entry<ModelRoute.Connections> { ConnectionsPage() }
    entry<ModelRoute.Connection> { ConnectionPage(it.id) }
    entry<ModelRoute.Connect> { ConnectPage(it.service, it.editId.takeIf(String::isNotEmpty)) }
    entry<ModelRoute.Browse> { ModelHomePage(filter = it.facet to it.option) }
    entry<ModelRoute.Tags> { AutoTagsAwareTagsPage() }
}

/** Open the model flow in a modal over the current page, at [start]. Selecting a model records it; chat follows. */
fun Navigator.openModelFlow(start: ModelRoute = ModelRoute.Home) = openModal(ModelFlowId, start)

private const val ModelFlowId = "models"

/** The Tags page with the models' automatic tags; tapping one opens Models filtered by it. */
@Composable
private fun AutoTagsAwareTagsPage() {
    val nav = navigator()
    TagsPage(rememberModelAutoTags(), onOpenAutomatic = { nav.navigate(ModelRoute.Browse(it.facet, it.option)) })
}
