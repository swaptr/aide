package com.swaptr.aide.ui.common

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp

sealed interface AppDropdownItem

data object AppDropdownDivider : AppDropdownItem

@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<AppDropdownItem>,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
    ) {
        items.forEach { item ->
            when (item) {
                is AppDropdownDivider -> HorizontalDivider()
                is AppMenuAction -> DropdownMenuItem(
                    enabled = item.enabled,
                    leadingIcon = item.iconRes?.let { res ->
                        {
                            Icon(
                                painter = painterResource(res),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = if (item.destructive) MaterialTheme.colorScheme.error
                                else LocalContentColor.current,
                            )
                        }
                    },
                    text = {
                        Text(
                            text = item.label,
                            color = if (item.destructive) MaterialTheme.colorScheme.error
                            else Color.Unspecified,
                        )
                    },
                    onClick = {
                        onDismissRequest()
                        item.onClick()
                    },
                )
            }
        }
    }
}
