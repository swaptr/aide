package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.util.ProviderHttp
import io.ktor.client.HttpClient

/**
 * Any self-hosted or third-party server that speaks the OpenAI Responses wire — the `open-responses`
 * spec, which is that wire published as a standard.
 *
 * This is to the Responses API what [com.sabreware.aide.aisdk.providers.openaicompatible.Vendors.custom]
 * is to Chat Completions: an escape hatch for a server nobody has added a table entry for. [url] is the
 * complete POST endpoint rather than a base, because these servers do not agree on a path — one serves
 * `/v1/responses`, another `/openai/responses` behind a gateway — and guessing a suffix produces a 404
 * with a working server behind it.
 *
 * [name] is load-bearing, not a label: the model reports `"<name>.responses"` as its provider id (the
 * reference's convention), and `providerOptions`/`providerMetadata` for this endpoint file under
 * `<name>` — with canonical `openai` always read underneath, custom key winning field by field — so two
 * open-responses servers in one app do not read each other's options.
 *
 * [extensions] is what makes such a server more than OpenAI's wire under another URL: the spec lets an
 * implementor add tools, items and streaming events under its own `ns:kind` types, and an
 * [OpenResponsesExtension] is how a caller tells this provider what those mean. They are validated
 * here, once, at construction — a half-declared extension is an `IllegalArgumentException` now rather
 * than an item silently ignored later.
 *
 * [strictResponseInput] serializes assistant history in the spec's own input schemas rather than
 * OpenAI's — see `ResponsesQuirks.strictResponseInput`. Off by default, because most servers accept
 * OpenAI's shape and the strict one drops the item ids OpenAI's replay depends on.
 */
public class OpenResponsesProvider(
    client: HttpClient,
    private val url: String,
    name: String,
    apiKey: String? = null,
    private val extraHeaders: Map<String, String> = emptyMap(),
    extensions: List<OpenResponsesExtension> = emptyList(),
    strictResponseInput: Boolean = false,
) : Provider {

    private val quirks = openResponsesQuirks(strictResponseInput)

    override val providerId: String = "$name.responses"

    private val namespace: String = name

    private val http = ProviderHttp(client)

    private val registry = OpenResponsesExtensionRegistry(extensions)

    private val headers: Map<String, String> = buildMap {
        apiKey?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        putAll(extraHeaders)
    }

    override fun languageModel(modelId: String): LanguageModel = OpenAIResponsesLanguageModel(
        modelId = modelId,
        http = http,
        headers = headers,
        provider = providerId,
        namespace = namespace,
        endpointUrl = url,
        quirks = quirks,
        extensions = registry,
    )
}
