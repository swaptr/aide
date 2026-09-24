package com.sabreware.aide.aisdk.providers.vertex

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.openaicompatible.OpenAICompatibleProvider
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An OpenAI-compatible provider that follows the Vertex bearer token.
 *
 * Two facts that do not fit together. [OpenAICompatibleProvider] takes its `apiKey` once, at
 * construction, and is immutable by design: every model it hands out bakes the `Authorization` header
 * in. Vertex's bearer is an OAuth2 access token that rotates roughly hourly, which is why every other
 * model in this package takes a `suspend () -> String` and re-resolves it per request (see
 * [VertexProvider]). Building the compat provider once with a snapshot of the token works until the
 * token rotates and then fails as a 401 that reads like a bad key.
 *
 * So the compat provider is built lazily and KEYED BY THE TOKEN it was built with: each request
 * resolves the token first, and the provider is rebuilt only when the token has changed. Rebuilding is
 * cheap — a `ProviderHttp` over the same client — so the cache is about shape rather than cost: one
 * provider per token, not one per request, and never one that outlives its token. The alternative —
 * teaching the compat provider a suspend header source — would put a per-vendor auth concern into the
 * one class every OpenAI-compatible vendor shares; the wrapping stays on this side of that seam.
 */
internal class VertexBearerCompat(
    private val accessToken: suspend () -> String,
    private val build: (bearer: String) -> OpenAICompatibleProvider,
) {

    private val lock = Mutex()
    private var current: Pair<String, OpenAICompatibleProvider>? = null

    /** The compat provider for the token as it is NOW — the token is re-resolved on every call. */
    suspend fun resolve(): OpenAICompatibleProvider {
        val token = accessToken()
        return lock.withLock {
            current?.takeIf { it.first == token }?.second
                ?: build(token).also { current = token to it }
        }
    }
}

/**
 * A language model that resolves its bearer at request time and delegates to the compat model built
 * for it — the model half of [VertexBearerCompat].
 *
 * Beyond the delegation it carries the two things a Vertex-hosted OpenAI-compatible model adds over the
 * compat one: [urls], because the compat model declares no fetchable URLs (a self-hosted server usually
 * cannot fetch, but a Vertex-hosted vision model can), and [adjust], the one place a call option can be
 * refused with a warning BEFORE the request is built — Grok on Vertex has exactly one such option.
 */
internal class VertexBearerLanguageModel(
    override val provider: String,
    override val modelId: String,
    private val compat: VertexBearerCompat,
    private val urls: Map<String, List<Regex>> = emptyMap(),
    private val adjust: (CallOptions, MutableList<Warning>) -> CallOptions = { options, _ -> options },
) : LanguageModel {

    override suspend fun supportedUrls(): Map<String, List<Regex>> = urls

    override suspend fun doGenerate(options: CallOptions): GenerateResult {
        val warnings = mutableListOf<Warning>()
        val result = delegate().doGenerate(adjust(options, warnings))
        return if (warnings.isEmpty()) result else result.copy(warnings = result.warnings + warnings)
    }

    override suspend fun doStream(options: CallOptions): StreamResult {
        val warnings = mutableListOf<Warning>()
        val result = delegate().doStream(adjust(options, warnings))
        if (warnings.isEmpty()) return result
        return result.copy(
            stream = result.stream.map { part ->
                if (part is StreamPart.StreamStart) part.copy(warnings = part.warnings + warnings) else part
            },
        )
    }

    /** Non-null by construction: every compat provider built here serves the chat modality. */
    private suspend fun delegate(): LanguageModel = requireNotNull(compat.resolve().languageModel(modelId))
}
