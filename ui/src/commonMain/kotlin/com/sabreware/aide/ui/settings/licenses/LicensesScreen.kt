package com.sabreware.aide.ui.settings.licenses

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.mikepenz.aboutlibraries.entity.Library
import com.sabreware.aide.core.designsystem.AppListItem
import com.sabreware.aide.core.designsystem.AppScaffold
import com.sabreware.aide.core.designsystem.ConstrainedContent
import com.sabreware.aide.core.designsystem.Placeholder
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.designsystem.state.StatePane
import com.sabreware.aide.core.designsystem.state.UiState

@Composable
fun LicensesScreen(
    onOpenLicense: (libraryId: String) -> Unit,
) {
    val libraries by rememberOssLibraries()
    AppScaffold(
        title = "Open source licenses",
    ) { scaffoldModifier ->
        ConstrainedContent(scaffoldModifier) { contentModifier ->
            val libs = libraries
            StatePane(
                state = if (libs == null) UiState.Loading else UiState.Ready(libs),
                modifier = contentModifier,
            ) { list ->
                if (list.isEmpty()) {
                    Placeholder(
                        modifier = Modifier.fillMaxSize(),
                        title = "No libraries",
                        subtitle = "No open-source dependencies were found.",
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(list, key = { it.uniqueId }) { library ->
                            AppListItem(
                                headline = library.name.ifBlank { library.uniqueId },
                                supportingText = library.licenseSummary(),
                                onClick = { onOpenLicense(library.uniqueId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Library.licenseSummary(): String? {
    val names = licenses
        .mapNotNull { it.spdxId?.ifBlank { null } ?: it.name.ifBlank { null } }
        .distinct()
        .joinToString(", ")
    return listOfNotNull(names.ifBlank { null }, artifactVersion)
        .joinToString(" · ")
        .ifBlank { null }
}
