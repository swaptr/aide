package com.swaptr.aide.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swaptr.aide.R

@Immutable
data class AppMenuEntry(
    val title: String,
    val subtitle: String? = null,
    val subtitleContent: (@Composable () -> Unit)? = null,
    @param:DrawableRes val leadingIconRes: Int? = null,
    val leadingIcon: (@Composable () -> Unit)? = null,
    val trailing: (@Composable () -> Unit)? = null,
    val onClick: (() -> Unit)? = null,
    val onLongClick: (() -> Unit)? = null,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val destructive: Boolean = false,
    val key: Any? = null,
    val contextActions: List<AppMenuAction>? = null,
    val toggle: AppMenuToggle? = null,
)

@Immutable
data class AppMenuToggle(
    val checked: Boolean,
    val onCheckedChange: (Boolean) -> Unit,
    val enabled: Boolean = true,
)

@Composable
fun AppMenuList(
    items: List<AppMenuEntry>,
    modifier: Modifier = Modifier,
    title: String? = null,
    contentPadding: PaddingValues = PaddingValues(vertical = 4.dp),
    emptyMessage: String = "No items available",
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (!title.isNullOrEmpty()) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
            )
        }
        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = emptyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                items.forEach { entry -> MenuItemRow(entry) }
            }
        }
    }
}

@Composable
private fun MenuItemRow(entry: AppMenuEntry) {
    val titleColor = when {
        entry.destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    val contentAlpha = if (entry.enabled) 1f else 0.38f
    val rowBg = if (entry.selected) {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
    } else {
        Color.Transparent
    }

    var contextOpen by remember { mutableStateOf(false) }
    val effectiveLongClick: (() -> Unit)? = when {
        entry.contextActions != null -> { -> contextOpen = true }
        else -> entry.onLongClick
    }

    val clickMod = when {
        entry.toggle != null -> {
            val tog = entry.toggle
            val toggleEnabled = entry.enabled && tog.enabled
            if (effectiveLongClick != null) {
                Modifier.combinedClickable(
                    enabled = toggleEnabled,
                    role = Role.Switch,
                    onClick = { tog.onCheckedChange(!tog.checked) },
                    onLongClick = effectiveLongClick,
                )
            } else {
                Modifier.toggleable(
                    value = tog.checked,
                    enabled = toggleEnabled,
                    role = Role.Switch,
                    onValueChange = tog.onCheckedChange,
                )
            }
        }
        entry.onClick != null || effectiveLongClick != null -> Modifier.combinedClickable(
            enabled = entry.enabled,
            role = Role.Button,
            onClick = { entry.onClick?.invoke() },
            onLongClick = effectiveLongClick,
        )
        else -> Modifier
    }

    Box(modifier = Modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .then(clickMod)
            .heightIn(min = if (entry.subtitle != null || entry.subtitleContent != null) 64.dp else 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val leading = entry.leadingIcon ?: entry.leadingIconRes?.let { res ->
            @Composable {
                Icon(
                    painter = painterResource(res),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        if (leading != null) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides LocalContentColor.current.copy(alpha = contentAlpha),
                ) { leading() }
            }
            Spacer(Modifier.width(16.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = titleColor.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.subtitleContent != null) {
                Spacer(Modifier.height(4.dp))
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                ) { entry.subtitleContent.invoke() }
            } else if (!entry.subtitle.isNullOrEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = entry.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val resolvedTrailing: (@Composable () -> Unit)? = entry.trailing ?: entry.toggle?.let { tog ->
            {
                Switch(
                    checked = tog.checked,
                    onCheckedChange = null,
                    enabled = entry.enabled && tog.enabled,
                )
            }
        }
        if (resolvedTrailing != null) {
            Spacer(Modifier.width(8.dp))
            Box(contentAlignment = Alignment.CenterEnd) {
                CompositionLocalProvider(
                    LocalContentColor provides LocalContentColor.current.copy(alpha = contentAlpha),
                ) { resolvedTrailing() }
            }
        }
    }
        if (entry.contextActions != null) {
            AppDropdownMenu(
                expanded = contextOpen,
                onDismissRequest = { contextOpen = false },
                items = entry.contextActions,
            )
        }
    }
}

@Composable
fun AppMenuTrailingSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
    )
}

data class AppMenuAction(
    val label: String,
    @param:DrawableRes val iconRes: Int? = null,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
) : AppDropdownItem

@Composable
fun AppMenuTrailingOverflow(
    actions: List<AppMenuAction>,
    contentDescription: String = "Actions",
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                painter = painterResource(R.drawable.ic_lc_ellipsis_vertical),
                contentDescription = contentDescription,
            )
        }
        AppDropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            items = actions,
        )
    }
}
