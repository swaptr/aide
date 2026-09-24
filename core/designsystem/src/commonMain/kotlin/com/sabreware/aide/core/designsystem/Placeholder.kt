package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.theme.AppSpacing
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * One call-to-action under a [Placeholder]. In a placeholder's [actions] list the first entry renders
 * as the filled primary button; the rest render as text (secondary) buttons.
 */
@Immutable
data class PlaceholderAction(
    val label: String,
    val onClick: () -> Unit,
    val iconRes: DrawableResource? = null,
    val enabled: Boolean = true,
)

/**
 * The standard empty/placeholder state: an optional [iconRes], optional [title]/[subtitle], and an
 * optional column of [actions] (first = primary, rest = secondary). Use it for any "nothing here yet"
 * surface so they all look the same.
 *
 * It anchors its content near the TOP of whatever space the [modifier] gives it (not vertically
 * centered): pass `Modifier.fillMaxSize()` for a full-screen empty state and the content sits slightly
 * up the page with whitespace below — which also keeps it fully visible when the host is a sheet at its
 * peek detent (centering would push it past the peek edge). `Modifier.fillMaxWidth()` sits inline at the
 * top of a scrolling page or list. All layout numbers live here, never at call sites, so every empty
 * state matches.
 */
@Composable
fun Placeholder(
    modifier: Modifier = Modifier,
    iconRes: DrawableResource? = null,
    title: String? = null,
    subtitle: String? = null,
    actions: List<PlaceholderAction> = emptyList(),
) {
    PlaceholderLayout(modifier) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(AppSpacing.lg))
        }
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
        if (subtitle != null) {
            if (title != null) Spacer(Modifier.height(AppSpacing.sm))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actions.isNotEmpty()) {
            Spacer(Modifier.height(AppSpacing.xl))
            actions.forEachIndexed { index, action ->
                if (index > 0) Spacer(Modifier.height(AppSpacing.sm))
                PlaceholderActionButton(action = action, primary = index == 0)
            }
        }
    }
}

@Composable
private fun PlaceholderActionButton(action: PlaceholderAction, primary: Boolean) {
    val content: @Composable RowScope.() -> Unit = {
        if (action.iconRes != null) {
            Icon(
                painter = painterResource(action.iconRes),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        }
        Text(action.label)
    }
    if (primary) {
        Button(onClick = action.onClick, enabled = action.enabled, content = content)
    } else {
        TextButton(onClick = action.onClick, enabled = action.enabled, content = content)
    }
}

/**
 * Where a [PlaceholderLayout] anchors its content: [topInset] down from the top, or [topFraction] of the
 * available height when that is further (only when the height is bounded — an unbounded host has no height
 * to take a fraction of). The default is the app-wide position; a surface that wants its empty state lower
 * provides a larger one through [LocalPlaceholderStyle] rather than padding by hand.
 */
@Immutable
data class PlaceholderStyle(
    val topInset: Dp = AppSpacing.xl,
    val topFraction: Float = 0f,
) {
    companion object {
        /** A hero empty state (the chat home): at least twice the default inset, and about a quarter of the
         *  way down the pane — near the centre, but above it. */
        val Hero = PlaceholderStyle(topInset = AppSpacing.xl * 2, topFraction = 0.25f)
    }
}

val LocalPlaceholderStyle = staticCompositionLocalOf { PlaceholderStyle() }

/**
 * The frame every empty state is drawn in: a horizontally centred column anchored near the TOP of the
 * space [modifier] gives it, positioned by [LocalPlaceholderStyle]. [Placeholder] is the stock content for it;
 * a surface whose empty state needs richer content (the chat greeting, with its animated model prompt) puts
 * that content here instead of positioning it by hand, so every empty state in the app sits in the same place.
 *
 * It is never clipped. Given a bounded height (a pane, a sheet page) it scrolls its own overflow — a phone
 * in landscape is shorter than a greeting plus its call to action. Given an unbounded one it is already
 * inside something that scrolls (a lazy list item, a scrolling page or dialog body), and adding a second
 * same-axis scroller there would throw, so it lays out at its natural height and lets that parent scroll.
 */
@Composable
fun PlaceholderLayout(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val style = LocalPlaceholderStyle.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val bounded = constraints.hasBoundedHeight
        val top = if (bounded) maxOf(style.topInset, maxHeight * style.topFraction) else style.topInset
        val scroll = rememberScrollState()
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .then(if (bounded) Modifier.verticalScroll(scroll) else Modifier)
                .padding(start = 32.dp, end = 32.dp, top = top, bottom = AppSpacing.xl),
            content = content,
        )
    }
}
