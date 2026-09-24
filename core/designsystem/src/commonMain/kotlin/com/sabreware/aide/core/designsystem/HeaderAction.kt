package com.sabreware.aide.core.designsystem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.sabreware.aide.core.designsystem.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * One icon button in a header, described as DATA and drawn by [AppHeader]. Every header in the app is an
 * [AppHeader] (the screen top bar via [AppScaffold] / [AppPage], a flow page via [PageScaffold], a sheet or
 * dialog via [AppDialog]) and takes the same two slots, both nullable and null by default:
 *
 * - `leadingAction: HeaderAction?` — the one button at the start. Null on a navigated page means the host's
 *   rule: a back chevron when the page can go back, otherwise nothing. Null on a leaf sheet means nothing.
 *   A top-level screen passes [drawer]; a mode passes its own exit (e.g. leaving selection).
 * - `trailingActions: List<HeaderAction>?` — the buttons at the end, in order. Null or empty draws nothing
 *   and reserves no width.
 *
 * A button either runs [onClick] or opens [menu] as an action sheet; exactly one is set. [label] is the
 * content description AND the identity: when the set of labels in a slot changes (start incognito becomes
 * exit incognito), the slot crossfades; when only [enabled] changes it updates in place. [destructive] tints
 * an enabled button with the error colour.
 */
@Immutable
data class HeaderAction(
    val iconRes: DrawableResource,
    val label: String,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val menu: HeaderMenu? = null,
    val onClick: (() -> Unit)? = null,
) {
    init {
        require((menu == null) != (onClick == null)) { "HeaderAction '$label' needs exactly one of onClick or menu" }
    }

    companion object {
        /** The back chevron. The navigated-page default; pass it explicitly only on a leaf sheet. */
        fun back(onClick: () -> Unit) = HeaderAction(Res.drawable.ic_lc_arrow_left, "Back", onClick = onClick)

        /**
         * The drawer (hamburger) button for a top-level screen. Present at every width: on compact it opens the
         * modal drawer, on wide it toggles the displacing sidebar, so the user can always reclaim the window.
         */
        fun drawer(onClick: () -> Unit) = HeaderAction(Res.drawable.ic_lc_menu, "Menu", onClick = onClick)
    }
}

/** The action sheet a [HeaderAction] opens: [items] as grouped rows under an optional [header]. */
@Immutable
data class HeaderMenu(
    val items: List<AppDropdownItem>,
    val header: AppMenuSheetHeader? = null,
)

/**
 * The one renderer for a header slot: each action as an [IconButton], crossfading when the slot's set of
 * labels changes. Every host draws its slots through this, so a button looks and behaves the same in a top
 * bar, a flow page and a sheet.
 */
@Composable
internal fun HeaderActionRow(actions: List<HeaderAction>) {
    AnimatedContent(
        targetState = actions,
        contentKey = { shown -> shown.map { it.label } },
        transitionSpec = { fadeIn(tween(HeaderActionFadeMs)) togetherWith fadeOut(tween(HeaderActionFadeMs)) },
        contentAlignment = Alignment.Center,
        label = "headerActions",
    ) { shown ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            shown.forEach { action -> key(action.label) { HeaderActionButton(action) } }
        }
    }
}

@Composable
private fun HeaderActionButton(action: HeaderAction) {
    var menuOpen by remember { mutableStateOf(false) }
    val menu = action.menu
    IconButton(
        onClick = { if (menu != null) menuOpen = true else action.onClick?.invoke() },
        modifier = Modifier.size(LocalHeaderBandStyle.current.slotSize),
        enabled = action.enabled,
    ) {
        Icon(
            painter = painterResource(action.iconRes),
            contentDescription = action.label,
            // Inside the button, LocalContentColor is already the enabled/disabled colour the button chose.
            tint = if (action.destructive && action.enabled) MaterialTheme.colorScheme.error else LocalContentColor.current,
        )
    }
    if (menu != null) {
        AppDropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            items = menu.items,
            header = menu.header,
        )
    }
}

private const val HeaderActionFadeMs = 180
