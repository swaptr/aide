package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.theme.AppSpacing

/**
 * [AppMenu] for a list too long to compose at once: one section of a `LazyColumn`, drawn as the SAME rounded,
 * segmented block — outer corners rounded, square inside, transparent seams — under an optional
 * [AppMenuSectionTitle]. Every list in the app is either this or a plain [AppMenu], so a page never mixes flat
 * full-width rows with rounded blocks. [row] is usually an [AppListItem] (it picks up the in-card metrics);
 * each row is its own lazy item, so a section of hundreds of models still composes only what is on screen.
 */
fun <T> LazyListScope.appMenuSection(
    items: List<T>,
    key: (T) -> Any,
    title: String? = null,
    row: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    title?.let { item(key = "section:$it") { AppMenuSectionTitle(it) } }
    itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
        AppMenuSegment(
            shape = appMenuShape(index, items.size),
            modifier = Modifier.padding(
                start = AppMenuGroupInset,
                end = AppMenuGroupInset,
                top = if (index == 0) AppSpacing.xs else AppMenuGap,
                bottom = if (index == items.lastIndex) AppSpacing.xs else 0.dp,
            ),
        ) { row(item) }
    }
}

/** [appMenuSection] over plain [AppMenuEntry] data — the lazy twin of `AppMenu(items)`. */
fun LazyListScope.appMenuEntries(items: List<AppMenuEntry>, title: String? = null) =
    appMenuSection(items, key = { it.key ?: it.title }, title = title) { MenuEntryRow(it) }
