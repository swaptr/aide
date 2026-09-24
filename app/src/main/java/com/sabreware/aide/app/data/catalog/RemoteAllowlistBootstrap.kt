package com.sabreware.aide.app.data.catalog

import com.sabreware.aide.core.domain.catalog.ModelCatalog
import com.sabreware.aide.core.common.startup.DeferredBootstrap
import android.content.Context
import com.sabreware.aide.core.domain.util.AideLog
import com.sabreware.aide.data.net.KtorClientFactory
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Refreshes the model allowlist from [AllowlistLoader.REMOTE_URL] on launch (background, one-shot) and caches
 * it to disk for the NEXT launch ([ModelCatalog] has already memoised this run's specs). No-op when the URL
 * is unset; failures are swallowed so the bundled asset / last cache keeps serving. Mirrors `ActiveSpeechBootstrap`.
 */
class RemoteAllowlistBootstrap(
    private val context: Context,
    private val scope: CoroutineScope,
) : DeferredBootstrap {

    private var started = false

    override suspend fun start() {
        // Idempotent, and deferred: this is a network fetch that applies on the NEXT launch, so there was
        // never a reason for it to run inside Application.onCreate ahead of the first frame.
        if (started) return
        started = true
        val url = AllowlistLoader.REMOTE_URL
        if (url.isBlank()) return
        scope.launch {
            runCatching {
                val client = KtorClientFactory.finite()
                try {
                    AllowlistLoader.writeCache(context, client.get(url).bodyAsText())
                } finally {
                    client.close()
                }
            }.onFailure { AideLog.w("AllowlistFetch", "remote allowlist fetch failed: ${it.message}") }
        }
    }
}
