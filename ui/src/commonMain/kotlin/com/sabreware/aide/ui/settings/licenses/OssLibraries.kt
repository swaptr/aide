package com.sabreware.aide.ui.settings.licenses

import com.sabreware.aide.core.domain.licenses.AboutLibrariesJson
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/** Loads the OSS dependency/license metadata the AboutLibraries plugin generates into
 *  `R.raw.aboutlibraries` at build time (not a checked-in file). `null` = still loading. */
@Composable
fun rememberOssLibraries(): State<List<Library>?> {
    val jsonProvider = koinInject<AboutLibrariesJson>()
    return produceState<List<Library>?>(initialValue = null, jsonProvider) {
        // load() does its own IO; the JSON parse below is CPU-bound, so hop to Default
        // (Dispatchers.IO is JVM/Native-only, not available in commonMain).
        val json = jsonProvider.load()
        value = withContext(Dispatchers.Default) {
            Libs.Builder().withJson(json).build().libraries
                .sortedBy { it.name.lowercase() }
        }
    }
}
