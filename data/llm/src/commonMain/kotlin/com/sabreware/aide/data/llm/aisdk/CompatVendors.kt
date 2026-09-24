package com.sabreware.aide.data.llm.aisdk

import com.sabreware.aide.aisdk.providers.openaicompatible.OpenAICompatibleModality
import com.sabreware.aide.aisdk.providers.openaicompatible.OpenAICompatibleProvider
import com.sabreware.aide.aisdk.providers.openaicompatible.Vendors
import com.sabreware.aide.core.domain.connection.VendorId
import io.ktor.client.HttpClient
import io.ktor.http.Url

/**
 * The `:aisdk` vendor row for a base URL.
 *
 * ONE vendor stands for every server that speaks Chat Completions — they differ only in base URL and key,
 * so each is simply a connection of that vendor with its own endpoint and credential. What differs is the
 * handful of things each vendor gets wrong or does differently: where Groq reports its token counts,
 * which effort levels Fireworks accepts, whether Ollama tolerates `stream_options`, and — the one that
 * bit first — which endpoints exist at all. `Vendors.openAI` claims image, speech and transcription,
 * so pointing it at an Ollama URL advertised three modalities that answer 404. The row decides quirks
 * and the modality set; the connection decides where the credential lives.
 *
 * Matched on the host rather than the full URL: every keyed vendor here serves from one canonical base,
 * so a stored `https://api.groq.com/openai/v1/` (or without the trailing path) is the same server. The
 * self-hosted runtimes are matched on their conventional port because their host is whatever machine
 * the user runs them on.
 *
 * An unknown host gets [Vendors.custom] with inline-reasoning extraction on — today's behaviour for
 * every URL, and the right one for a server nobody has characterised: chat only, so a caller learns an
 * endpoint is absent from a null model rather than from a user's 404.
 *
 * A known vendor with NO key still gets its own row. The row decides which endpoints exist, and that
 * does not depend on the credential: an OpenAI URL without a key must still say "yes, images" so the
 * failure a user then sees is the vendor's own 401 ("you didn't provide an API key") rather than a
 * fabricated "serves no image models". A blank key sends no `Authorization` header at all.
 */
public object CompatVendors {

    public fun resolve(client: HttpClient, baseUrl: String, apiKey: String?): OpenAICompatibleProvider {
        val url = baseUrl.trim().trimEnd('/')
        val key = apiKey?.takeIf { it.isNotBlank() }
        val parsed = runCatching { Url(url) }.getOrNull()
        val host = parsed?.host?.lowercase().orEmpty()
        val port = parsed?.port ?: -1
        return when (host) {
            "api.openai.com" -> Vendors.openAI(client, key.orEmpty(), url)
            "openrouter.ai" -> Vendors.openRouter(client, key.orEmpty(), appTitle = APP_TITLE)
            "api.groq.com" -> Vendors.groq(client, key.orEmpty())
            "api.together.xyz" -> Vendors.together(client, key.orEmpty())
            "api.fireworks.ai" -> Vendors.fireworks(client, key.orEmpty())
            "api.deepinfra.com" -> Vendors.deepInfra(client, key.orEmpty())
            "api.cerebras.ai" -> Vendors.cerebras(client, key.orEmpty())
            // Sonar Chat Completions retires 2026-09-27 and `Vendors.perplexity` went with it; the successor
            // is `PerplexityProvider.languageModel` on the Agent API, a different wire that needs its own
            // AIDE provider id (DEFERRED.md). Until then the legacy endpoint is a plain compat server:
            // chat only, no vendor usage arithmetic.
            "api.perplexity.ai" -> Vendors.custom(
                client = client,
                providerId = PERPLEXITY_ID,
                baseUrl = url,
                apiKey = key,
                extractInlineReasoning = true,
            )
            "api.x.ai" -> Vendors.xai(client, key.orEmpty())
            "router.huggingface.co" -> Vendors.huggingFace(client, key.orEmpty())
            "api.gmi-serving.com" -> Vendors.gmiCloud(client, key.orEmpty())
            "inference.baseten.co" -> Vendors.baseten(client, key.orEmpty())
            "ai-gateway.vercel.sh" -> Vendors.vercelGateway(client, key.orEmpty())
            else -> resolveSelfHosted(client, url, key, host, port)
        }
    }

    private fun resolveSelfHosted(
        client: HttpClient,
        url: String,
        key: String?,
        host: String,
        port: Int,
    ): OpenAICompatibleProvider {
        return when {
            // Ollama Cloud is Ollama's wire behind a key; the keyless `ollama()` row cannot carry one.
            host == "ollama.com" -> Vendors.custom(
                client = client,
                providerId = OLLAMA_ID,
                baseUrl = url,
                apiKey = key,
                extractInlineReasoning = true,
                modalities = setOf(OpenAICompatibleModality.Chat, OpenAICompatibleModality.Embedding),
            )
            port == OLLAMA_PORT -> Vendors.ollama(client, url)
            port == LM_STUDIO_PORT -> Vendors.lmStudio(client, url)
            else -> Vendors.custom(
                client = client,
                providerId = VendorId.OPENAI_COMPATIBLE.value,
                baseUrl = url,
                apiKey = key,
                // Compat routers may inline reasoning as <think> tags; the native reasoning_content and
                // reasoning_details channels are handled regardless.
                extractInlineReasoning = true,
            )
        }
    }

    private const val APP_TITLE = "AIDE"
    private const val OLLAMA_ID = "ollama"
    private const val PERPLEXITY_ID = "perplexity"
    private const val OLLAMA_PORT = 11434
    private const val LM_STUDIO_PORT = 1234
}
