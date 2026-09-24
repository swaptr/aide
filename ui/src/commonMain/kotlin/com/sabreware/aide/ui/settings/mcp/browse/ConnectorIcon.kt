package com.sabreware.aide.ui.settings.mcp.browse

import com.sabreware.aide.core.designsystem.LeadingMediaShape
import com.sabreware.aide.core.designsystem.theme.LocalAppListItemStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.sabreware.aide.core.domain.connector.Connector
import com.sabreware.aide.core.domain.connector.IconResolver
import kotlin.math.absoluteValue

/**
 * A connector's logo, fetched at runtime via the connector [ImageLoader] (apple-touch-icon → favicon →
 * Simple Icons CDN; see [IconResolver]). Falls back to a colored monogram if every candidate fails.
 *
 * Uses [AsyncImage] (NOT `SubcomposeAsyncImage`) so catalog rows stay cheap to measure in the LazyColumn —
 * Coil only fetches when the row is actually composed, i.e. scrolled into view. No bundled assets.
 */
@Composable
fun ConnectorIcon(
    connector: Connector,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    size: Dp = LocalAppListItemStyle.current.mediaSize,
) {
    val context = LocalPlatformContext.current
    val request = remember(connector.id) {
        ImageRequest.Builder(context).data(IconResolver.request(connector)).crossfade(true).build()
    }
    var failed by remember(connector.id) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(size)
            .clip(LeadingMediaShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (failed) {
            ConnectorMonogram(connector.name, size)
        } else {
            AsyncImage(
                model = request,
                imageLoader = imageLoader,
                contentDescription = null,
                modifier = Modifier.size(size),
                onState = { state -> if (state is AsyncImagePainter.State.Error) failed = true },
            )
        }
    }
}

@Composable
private fun ConnectorMonogram(name: String, size: Dp) {
    val letter = name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
    val palette = with(MaterialTheme.colorScheme) { listOf(primaryContainer, secondaryContainer, tertiaryContainer) }
    val bg = palette[name.hashCode().absoluteValue % palette.size]
    Box(
        modifier = Modifier.size(size).clip(LeadingMediaShape).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = letter, style = MaterialTheme.typography.labelLarge, color = contentColorFor(bg))
    }
}
