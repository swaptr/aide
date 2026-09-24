package com.sabreware.aide.core.designsystem.browse

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sabreware.aide.core.designsystem.AppDialog
import com.sabreware.aide.core.designsystem.AppMenu
import com.sabreware.aide.core.designsystem.AppMenuEntry
import com.sabreware.aide.core.designsystem.HeaderAction
import com.sabreware.aide.core.designsystem.Placeholder
import com.sabreware.aide.core.designsystem.PlaceholderAction
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.domain.browse.BrowseQuery
import com.sabreware.aide.core.domain.browse.BrowseResult
import com.sabreware.aide.core.domain.browse.BrowseSpec
import com.sabreware.aide.core.domain.browse.FacetState

// -------------------------------------------------------------------------------------------------------
// The browse kit: the ONE look for searching, filtering and acting on a collection. The engine is
// `com.sabreware.aide.core.domain.browse` (a spec per collection); this renders its result the same way on
// every surface — search and Filter as header actions ([collectionBar]), a facet sheet with live counts whose
// chosen options are brightened like every settings picker, and item actions as data.
// -------------------------------------------------------------------------------------------------------

/**
 * What the user typed and chose, surviving rotation and process death (it is primitives only). [base] is the
 * query the page was opened with (a tag's filter); leaving search returns to it rather than to nothing, so a
 * page opened pre-filtered never turns into its unfiltered self on the way back.
 */
@Stable
class BrowseState internal constructor(
    private val base: BrowseQuery,
    query: BrowseQuery = base,
    searching: Boolean = false,
) {
    var query: BrowseQuery by mutableStateOf(query)

    /** Whether the header has turned into the search field ([collectionBar]). */
    var searching: Boolean by mutableStateOf(searching)
        private set

    fun openSearch() { searching = true }

    /** Leaves search and drops what was typed AND the filters chosen in it, back to what the page opened with. */
    fun closeSearch() {
        searching = false
        clear()
    }

    fun setText(text: String) { query = query.withText(text) }
    fun toggle(facet: String, option: String) { query = query.toggle(facet, option) }
    fun choose(facet: String, option: String) { query = query.choose(facet, option) }
    fun clearFilters() { query = query.clearFilters() }
    fun clear() { query = base }
}

/**
 * A [BrowseState] saved with the host, optionally opened with [initial] (a preselected facet). A pre-filtered
 * page opens as the filtered list, not as a search: no keyboard, its filters shown by the badged Filter.
 */
@Composable
fun rememberBrowseState(initial: BrowseQuery = BrowseQuery()): BrowseState =
    rememberSaveable(initial, saver = browseStateSaver(initial)) { BrowseState(initial) }

private const val SEP = '\u0000'

private fun browseStateSaver(base: BrowseQuery): Saver<BrowseState, List<String>> = Saver(
    save = { state ->
        listOf(if (state.searching) "1" else "0", state.query.text) + state.query.filters.flatMap { (facet, options) -> options.map { "$facet$SEP$it" } }
    },
    restore = { saved ->
        val filters = saved.drop(2).groupBy({ it.substringBefore(SEP) }, { it.substringAfter(SEP) }).mapValues { it.value.toSet() }
        BrowseState(base, BrowseQuery(saved.getOrNull(1).orEmpty(), filters), searching = saved.firstOrNull() == "1")
    },
)

/**
 * [spec] run over [items] for the current query. Recomputed only when the items, the spec or the query
 * change — never per frame — and it is a pure pass over an in-memory list, so it is cheap even at the size
 * of a large cloud catalog.
 */
@Composable
fun <T> rememberBrowseResult(items: List<T>, spec: BrowseSpec<T>, state: BrowseState): BrowseResult<T> =
    remember(items, spec, state.query) { spec.run(items, state.query) }

/**
 * Every facet as a section of options, each with the count it would leave. Choosing applies at once: the list
 * behind the sheet updates live, so there is no Apply button to forget.
 */
@Composable
fun BrowseFilterSheet(
    state: BrowseState,
    facets: List<FacetState>,
    countLabel: (Int) -> String,
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismiss = onDismiss,
        title = "Filter",
        trailingActions = listOf(
            HeaderAction(Res.drawable.ic_lc_rotate_ccw, "Clear filters", enabled = state.query.activeFilterCount > 0, onClick = state::clearFilters),
        ),
    ) {
        facets.forEach { facet ->
            // The same rows as every settings picker: a chosen option is brightened, nothing more. Tapping it
            // again drops it (a one-at-a-time view returns to its default).
            AppMenu(
                title = facet.label,
                items = facet.options.map { option ->
                    AppMenuEntry(
                        key = "${facet.id}/${option.id}",
                        title = option.label,
                        subtitle = countLabel(option.count),
                        selected = option.selected,
                        onClick = { if (facet.exclusive) state.choose(facet.id, option.id) else state.toggle(facet.id, option.id) },
                    )
                },
            )
        }
    }
}

/** The one "nothing matches" look, with the way out. */
@Composable
fun <T> BrowseNoMatches(state: BrowseState, result: BrowseResult<T>, modifier: Modifier = Modifier) {
    Placeholder(
        modifier = modifier.fillMaxWidth(),
        iconRes = Res.drawable.ic_lc_search,
        title = "Nothing matches",
        subtitle = result.query.text.trim().takeIf { it.isNotEmpty() }?.let { "No results for “$it”." }
            ?: "No items match these filters.",
        actions = listOf(PlaceholderAction(label = "Clear", iconRes = Res.drawable.ic_lc_x, onClick = state::clear)),
    )
}
