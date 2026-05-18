package com.swaptr.aide.ui.settings.websearch

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.painterResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swaptr.aide.R
import com.swaptr.aide.ui.common.AppMenuEntry
import com.swaptr.aide.ui.common.AppMenuList
import com.swaptr.aide.ui.common.AppPage

@Composable
fun WebSearchSettingsScreen(
    onClose: () -> Unit,
    viewModel: WebSearchSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    AppPage(
        title = "Web search",
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(R.drawable.ic_lc_arrow_left),
                    contentDescription = "Back",
                )
            }
        },
    ) {
        AppMenuList(
            items = buildList {
                add(
                    AppMenuEntry(
                        title = "Auto",
                        subtitle = "Use the best available provider. Falls back to DuckDuckGo when no key is configured.",
                        selected = state.override == null,
                        onClick = { viewModel.setOverride(null) },
                    ),
                )
                state.rows.forEach { row ->
                    add(
                        AppMenuEntry(
                            title = row.displayName,
                            subtitle = row.note,
                            selected = state.override == row.id,
                            enabled = row.available,
                            onClick = { viewModel.setOverride(row.id) },
                        ),
                    )
                }
            },
        )
    }
}
