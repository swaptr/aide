package com.sabreware.aide.ui.settings.licenses

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.entity.License
import com.sabreware.aide.core.designsystem.AppMenu
import com.sabreware.aide.core.designsystem.AppMenuCard
import com.sabreware.aide.core.designsystem.AppMenuEntry
import com.sabreware.aide.core.designsystem.AppMenuSectionTitle
import com.sabreware.aide.core.designsystem.AppPage
import com.sabreware.aide.core.designsystem.Placeholder
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.designsystem.state.StatePane
import com.sabreware.aide.core.designsystem.state.UiState
import com.sabreware.aide.core.designsystem.theme.AppSpacing

/** [libraryId] is the AboutLibraries `uniqueId` ("group:artifact"). Licenses with no bundled text
 *  fall back to a link to their canonical URL. */
@Composable
fun LicenseDetailScreen(
    libraryId: String,
) {
    val libraries by rememberOssLibraries()
    val library = libraries?.firstOrNull { it.uniqueId == libraryId }
    AppPage(
        title = library?.name?.ifBlank { library.uniqueId } ?: "License",
    ) {
        val libs = libraries
        StatePane(
            state = if (libs == null) UiState.Loading else UiState.Ready(libs),
            // Scroll-hosted page: size the spinner to the width, not fillMaxSize (infinite height here).
            loading = {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(AppSpacing.xl),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            },
        ) { list ->
            val lib = list.firstOrNull { it.uniqueId == libraryId }
            if (lib == null) {
                Placeholder(
                    title = "License unavailable",
                    subtitle = "This library could not be found.",
                )
            } else {
                LibraryLicenses(lib)
            }
        }
    }
}

@Composable
private fun LibraryLicenses(library: Library) {
    val uriHandler = LocalUriHandler.current

    val meta = buildList {
        library.artifactVersion?.takeIf { it.isNotBlank() }?.let {
            add(AppMenuEntry(title = "Version", subtitle = it))
        }
        library.developers.mapNotNull { it.name }.joinToString(", ").takeIf { it.isNotBlank() }?.let {
            add(AppMenuEntry(title = "Developer", subtitle = it))
        }
        library.organization?.name?.takeIf { it.isNotBlank() }?.let {
            add(AppMenuEntry(title = "Organization", subtitle = it))
        }
        library.website?.takeIf { it.isNotBlank() }?.let { site ->
            add(AppMenuEntry(title = "Website", subtitle = site, onClick = { uriHandler.openUri(site) }))
        }
        add(AppMenuEntry(title = "Artifact", subtitle = library.uniqueId))
    }
    AppMenu(title = "Library", items = meta)

    library.licenses.forEach { license ->
        AppMenuSectionTitle(license.displayTitle())
        val content = license.licenseContent
        val url = license.url
        when {
            !content.isNullOrBlank() -> AppMenuCard {
                SelectionContainer {
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(AppSpacing.lg),
                    )
                }
            }

            !url.isNullOrBlank() -> AppMenu(
                items = listOf(
                    AppMenuEntry(
                        title = "View full license",
                        subtitle = url,
                        onClick = { uriHandler.openUri(url) },
                    ),
                ),
            )
        }
    }
    Spacer(Modifier.height(AppSpacing.xl))
}

private fun License.displayTitle(): String {
    val spdx = spdxId
    return if (!spdx.isNullOrBlank() && spdx != name) "$name ($spdx)" else name
}
