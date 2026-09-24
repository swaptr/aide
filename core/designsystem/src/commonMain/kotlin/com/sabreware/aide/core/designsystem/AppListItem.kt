package com.sabreware.aide.core.designsystem

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.theme.AppListItemStyle
import com.sabreware.aide.core.designsystem.theme.LocalAppListItemStyle
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * The single reusable list row for the whole app.
 *
 * Every leading visual sits in one fixed-width [LeadingSlot], so the headline starts at the same x whatever the
 * row leads with, and is sized by kind ([AppListItemStyle]): [leading]/[leadingIconRes] is a bare tinted glyph
 * at `glyphSize`; [leadingMedia] is a logo or avatar at `mediaSize`, clipped to [LeadingMediaShape], in its
 * own colors.
 * Every row is ONE line of headline over ONE line of supporting text, so rows of a kind are one height; a name
 * that does not fit ellipsizes at rest and walks as a [MarqueeText] when the page settles or the row is
 * hovered/focused. Slot-based: composable content (leading icon,
 * supporting line, trailing widget) is passed as parameters — never stored in a data class — so it
 * stays stable/skippable. Layout metrics come implicitly from [LocalAppListItemStyle]; there are no
 * per-call padding knobs. Use directly inside a `LazyColumn` for dynamic lists, or via [AppMenu]
 * for bounded static menus.
 */
@Composable
fun AppListItem(
    headline: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    supporting: (@Composable () -> Unit)? = null,
    headlineColor: Color? = null,
    supportingColor: Color? = null,
    leadingIconRes: DrawableResource? = null,
    leading: (@Composable () -> Unit)? = null,
    leadingMedia: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    destructive: Boolean = false,
    contextActions: List<AppMenuAction>? = null,
    contextHeader: AppMenuSheetHeader? = null,
) {
    val style = LocalAppListItemStyle.current
    val titleColor = headlineColor ?: when {
        destructive -> MaterialTheme.colorScheme.error
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val contentAlpha = if (enabled) 1f else 0.38f
    // A subtitle is always the quiet gray, chosen or not: the brighter fill is what says "chosen".
    val supportingTone = MaterialTheme.colorScheme.onSurfaceVariant
    // Selected rows need to read clearly against the card's surfaceContainerHigh fill. The full
    // secondaryContainer token is distinctly brighter (a faint alpha wash was nearly invisible on dark).
    val rowBg = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color.Transparent
    }
    val hasSupporting = supporting != null || !supportingText.isNullOrEmpty()

    var contextOpen by remember { mutableStateOf(false) }
    val effectiveLongClick: (() -> Unit)? = when {
        contextActions != null -> { -> contextOpen = true }
        else -> onLongClick
    }

    val clickable = onClick != null || effectiveLongClick != null
    // The row's hover/focus is its names' attention (see MarqueeText); a row nobody can press has none.
    val interactions = if (clickable) remember { MutableInteractionSource() } else null
    val clickMod = if (interactions != null) {
        Modifier.combinedClickable(
            interactionSource = interactions,
            indication = LocalIndication.current,
            enabled = enabled,
            role = Role.Button,
            onClick = { onClick?.invoke() },
            onLongClick = effectiveLongClick,
        )
    } else {
        Modifier
    }

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(rowBg)
                .then(clickMod)
                .heightIn(min = if (hasSupporting) style.minHeightTwoLine else style.minHeight)
                .padding(horizontal = style.contentInset, vertical = style.verticalInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val glyph = leading ?: leadingIconRes?.let { res ->
                @Composable {
                    Icon(
                        painter = painterResource(res),
                        contentDescription = null,
                        modifier = Modifier.size(style.glyphSize),
                    )
                }
            }
            // One fixed-width slot for every leading kind, so every headline starts at the same x; what sits
            // in it is sized by kind — media at mediaSize in its own colors, a glyph at glyphSize, bare.
            if (leadingMedia != null) {
                LeadingSlot(style) {
                    Box(
                        modifier = Modifier.size(style.mediaSize).clip(LeadingMediaShape).alpha(contentAlpha),
                        contentAlignment = Alignment.Center,
                    ) { leadingMedia() }
                }
            } else if (glyph != null) {
                val leadingTint = when {
                    destructive -> MaterialTheme.colorScheme.error
                    selected -> MaterialTheme.colorScheme.onSecondaryContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                LeadingSlot(style) {
                    Box(Modifier.size(style.glyphSize), contentAlignment = Alignment.Center) {
                        CompositionLocalProvider(
                            LocalContentColor provides leadingTint.copy(alpha = contentAlpha),
                        ) { glyph() }
                    }
                }
            }

            CompositionLocalProvider(LocalMarqueeAttention provides interactions) {
                Column(modifier = Modifier.weight(1f)) {
                    MarqueeText(
                        text = headline,
                        style = MaterialTheme.typography.titleMedium,
                        color = titleColor.copy(alpha = contentAlpha),
                    )
                    if (supporting != null) {
                        Spacer(Modifier.height(SupportingGap))
                        CompositionLocalProvider(
                            LocalContentColor provides supportingTone.copy(alpha = contentAlpha),
                        ) { supporting() }
                    } else if (!supportingText.isNullOrEmpty()) {
                        Spacer(Modifier.height(SupportingGap))
                        MarqueeText(
                            text = supportingText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = (supportingColor ?: supportingTone).copy(alpha = contentAlpha),
                        )
                    }
                }
            }

            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                Box(contentAlignment = Alignment.CenterEnd) {
                    CompositionLocalProvider(
                        LocalContentColor provides LocalContentColor.current.copy(alpha = contentAlpha),
                    ) { trailing() }
                }
            }
        }

        if (contextActions != null) {
            AppDropdownMenu(
                expanded = contextOpen,
                onDismissRequest = { contextOpen = false },
                items = contextActions,
                header = contextHeader,
            )
        }
    }
}

private val SupportingGap = 4.dp

/** The shape a row's leading media — and anything drawn to match it (a logo, its skeleton) — is clipped to. */
val LeadingMediaShape = CircleShape

/**
 * The leading column: [AppListItemStyle.leadingSlot] wide plus [AppListItemStyle.leadingGap], content centered.
 * Its height is its content's, so a glyph row never grows to the media size.
 */
@Composable
internal fun LeadingSlot(style: AppListItemStyle, content: @Composable () -> Unit) {
    Box(Modifier.width(style.leadingSlot), contentAlignment = Alignment.Center) { content() }
    Spacer(Modifier.width(style.leadingGap))
}
