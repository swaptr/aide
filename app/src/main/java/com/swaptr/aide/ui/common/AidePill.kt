package com.swaptr.aide.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun AidePill(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    color: Color = Color.Transparent,
    contentColor: Color = LocalContentColor.current,
    contentPadding: PaddingValues = PaddingValues(
        start = 14.dp,
        end = 12.dp,
        top = 8.dp,
        bottom = 8.dp,
    ),
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    label: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val body: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (leading != null) leading()
            label()
            if (trailing != null) trailing()
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            color = color,
            contentColor = contentColor.takeIf { it.isSpecified() } ?: MaterialTheme.colorScheme.onBackground,
        ) { body() }
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = color,
            contentColor = contentColor.takeIf { it.isSpecified() } ?: MaterialTheme.colorScheme.onBackground,
        ) { body() }
    }
}

private fun Color.isSpecified(): Boolean = this != Color.Unspecified
