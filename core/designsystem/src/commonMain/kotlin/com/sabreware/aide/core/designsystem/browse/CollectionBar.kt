package com.sabreware.aide.core.designsystem.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.backhandler.BackHandler
import com.sabreware.aide.core.designsystem.HeaderAction
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.designsystem.search.SearchField
import com.sabreware.aide.core.domain.browse.FacetState

/**
 * The header of any collection page — models, chats, connections, connectors, tags. Searching, filtering and
 * selecting are header actions, never controls parked in the list, so the list below stays a plain list and
 * every collection is driven from the same place:
 *
 * - **Browsing:** Search, Filter (only when there is something to filter by) and the page's own [actions],
 *   then [select]. Past the header's button budget the tail folds into More, so Search and Filter stay in view.
 * - **Searching:** the band becomes the [SearchField] — focused, keyboard up, no navigation. A back arrow (or
 *   system back) leaves search and drops the text; Filter stays beside the field. Pages search locally through
 *   their [BrowseState]; a page with a remote answer too feeds the same text to
 *   [com.sabreware.aide.core.designsystem.search.rememberSearchResults].
 * - **Selecting:** wrap the result in [collectionHeader], which takes over while selection mode is on.
 *
 * The filter sheet is hosted here. A chosen option is brightened in it; tapping it again un-chooses it.
 */
@Composable
fun collectionBar(
    title: String,
    browse: BrowseState,
    placeholder: String,
    facets: List<FacetState> = emptyList(),
    actions: List<HeaderAction> = emptyList(),
    select: HeaderAction? = null,
    leadingAction: HeaderAction? = null,
    /** False where the page has nothing to search (a tab of services to connect): no Search, no Filter. */
    searchable: Boolean = true,
    /** How a count reads in the filter sheet ("12 models"). */
    countLabel: (Int) -> String = { if (it == 1) "1 item" else "$it items" },
): CollectionHeader {
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    val chosen = browse.query.activeFilterCount
    val filter = HeaderAction(
        iconRes = Res.drawable.ic_lc_list_filter,
        label = if (chosen > 0) "Filter ($chosen)" else "Filter",
        onClick = { filtersOpen = true },
    ).takeIf { searchable && (facets.isNotEmpty() || chosen > 0) }
    if (filtersOpen) BrowseFilterSheet(browse, facets, countLabel, onDismiss = { filtersOpen = false })

    BackHandler(enabled = searchable && browse.searching) { browse.closeSearch() }
    if (searchable && browse.searching) {
        return CollectionHeader(
            title = title,
            leadingAction = HeaderAction(Res.drawable.ic_lc_arrow_left, "Close search", onClick = browse::closeSearch),
            trailingActions = listOfNotNull(filter),
            titleContent = { SearchField(browse.query.text, browse::setText, placeholder) },
        )
    }
    val search = HeaderAction(Res.drawable.ic_lc_search, "Search", onClick = browse::openSearch).takeIf { searchable }
    return CollectionHeader(
        title = title,
        leadingAction = leadingAction,
        trailingActions = listOfNotNull(search, filter) + actions + listOfNotNull(select),
    )
}
