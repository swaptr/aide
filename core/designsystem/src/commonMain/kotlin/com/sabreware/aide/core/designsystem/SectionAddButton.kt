package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.resources.*
import org.jetbrains.compose.resources.painterResource

/**
 * Filled "Add …" action — primary, centered. The shared add affordance for list sections (the Models
 * page, the Connectors settings page) so add buttons read identically whether or not the section
 * already has items.
 */
@Composable
fun SectionAddButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Button(onClick = onClick) {
            Icon(
                painter = painterResource(Res.drawable.ic_lc_plus),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(label)
        }
    }
}
