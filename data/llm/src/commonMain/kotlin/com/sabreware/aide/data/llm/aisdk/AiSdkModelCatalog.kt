package com.sabreware.aide.data.llm.aisdk

import com.sabreware.aide.aisdk.providers.anthropic.AnthropicProvider
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.core.domain.provider.ConnectionTestResult
import io.ktor.client.HttpClient
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One row of a `/models` listing: the wire id and whatever display name the vendor attached to it. */
public data class ListedModel(val id: String, val displayName: String? = null)

/**
 * Lists what a provider actually serves, and tests that it answers at all.
 *
 * The one endpoint the `:aisdk` specification does not model — a listing is how AIDE discovers what a
 * key can reach, not how it talks to a model — so it rides the same [ProviderHttp] transport every
 * provider does (retries, the vendor's error envelope read into a sentence) rather than a second Ktor
 * client with its own error handling.
 *
 * Two wires answer the same shape. Every OpenAI-compatible server implements `GET {base}/models` with a
 * `data[].id` array — it is how a user discovers what their local Ollama has pulled — and Anthropic's
 * `GET /v1/models` is that shape plus a `display_name`. The headers are the only difference, which is
 * why they are the caller's to build ([bearerHeaders], [anthropicHeaders]) rather than a vendor switch.
 */
public class AiSdkModelCatalog(client: HttpClient) {

    private val http = ProviderHttp(client)

    /**
     * The models the server reports, unfiltered.
     *
     * Unknown ids pass through rather than being dropped: a self-hosted server serves whatever its
     * operator loaded, and a client that only accepts ids it recognizes is useless against exactly the
     * setups that most need listing.
     */
    public suspend fun listModels(baseUrl: String, headers: Map<String, String>): List<ListedModel> {
        val response = http.getJson("${baseUrl.trimEnd('/')}/models", headers)
        return response.value.jsonObject["data"]
            ?.jsonArray
            ?.mapNotNull { row ->
                val entry = row.jsonObject
                entry["id"]?.jsonPrimitive?.contentOrNull?.let { id ->
                    ListedModel(id, entry["display_name"]?.jsonPrimitive?.contentOrNull)
                }
            }
            .orEmpty()
    }

    /**
     * Whether the server answers with credentials that work.
     *
     * Reported as a count rather than a bare boolean because "connected, zero models" is a real and
     * confusing state — a local runtime that is running but has nothing pulled — and it looks identical
     * to success unless the number is shown.
     */
    public suspend fun testConnection(baseUrl: String, headers: Map<String, String>): ConnectionTestResult =
        runCatching { listModels(baseUrl, headers) }.fold(
            onSuccess = { ConnectionTestResult.Ok(it.size) },
            onFailure = { ConnectionTestResult.Failed(it.message ?: it::class.simpleName ?: "error") },
        )

    public companion object {
        /** `Authorization: Bearer` — every OpenAI-compatible server; absent when the server is keyless. */
        public fun bearerHeaders(apiKey: String?): Map<String, String> =
            apiKey?.takeIf { it.isNotBlank() }?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()

        /**
         * Anthropic authenticates with `x-api-key` and rejects any request without the pinned
         * `anthropic-version` — the same version the `:aisdk` provider sends, so the listing and the
         * chat can never disagree about which API they are talking to.
         */
        public fun anthropicHeaders(apiKey: String?): Map<String, String> = buildMap {
            apiKey?.takeIf { it.isNotBlank() }?.let { put("x-api-key", it) }
            put("anthropic-version", AnthropicProvider.DEFAULT_API_VERSION)
        }
    }
}
