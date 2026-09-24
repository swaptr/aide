package com.sabreware.aide.core.designsystem.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.AppIconButton
import com.sabreware.aide.core.designsystem.resources.*
import kotlinx.coroutines.delay

/**
 * The ONE search input: chrome-free text in the title's size, a placeholder, and a clear button once there is
 * text. It takes focus (and so the keyboard) when it appears — opening search IS the request to type. Drawn in the
 * header band by [com.sabreware.aide.core.designsystem.browse.collectionBar], so every search in the app looks
 * and behaves the same; restyle it here and they all follow.
 */
@Composable
fun SearchField(
    text: String,
    onText: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    // The title's size at regular weight: it reads as typing, not as a heading.
    val style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) {
                Text(placeholder, style = style, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = text,
                onValueChange = onText,
                singleLine = true,
                textStyle = style.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (text.isNotEmpty()) {
            AppIconButton(onClick = { onText("") }, iconRes = Res.drawable.ic_lc_x, contentDescription = "Clear search", size = 40.dp, iconSize = 20.dp)
        }
    }
}

/** What a search [source] returned for [query], and whether that answer is in yet. */
@Immutable
data class SearchResults<T>(val query: String, val items: List<T>, val settled: Boolean) {
    val searching: Boolean get() = !settled
}

/**
 * A search whose answer comes from somewhere slow — an API, a database, a directory — rather than a list already
 * in memory. [source] is re-run as [text] changes, debounced by [debounceMs] while typing (a blank query runs at
 * once); the latest call wins, so a slow answer never overwrites a newer one. Pair it with the in-memory
 * [com.sabreware.aide.core.domain.browse.BrowseSpec] on the same text for a page that searches both.
 */
@Composable
fun <T> rememberSearchResults(
    text: String,
    debounceMs: Long = 250,
    source: suspend (query: String) -> List<T>,
): SearchResults<T> {
    var results by remember { mutableStateOf(SearchResults<T>("", emptyList(), settled = false)) }
    val currentSource by rememberUpdatedState(source)
    LaunchedEffect(text) {
        val q = text.trim()
        results = results.copy(settled = false)
        if (q.isNotEmpty()) delay(debounceMs)
        results = SearchResults(q, currentSource(q), settled = true)
    }
    return results
}
