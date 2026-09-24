package com.sabreware.aide.aisdk.providers.vertex

import com.sabreware.aide.aisdk.ProviderOptions
import com.sabreware.aide.aisdk.providers.google.GOOGLE_PROVIDER_ID
import com.sabreware.aide.aisdk.providers.options.mergedFor
import com.sabreware.aide.aisdk.providers.options.mergedForOrNull
import kotlinx.serialization.json.JsonObject

/**
 * The regional hostname rule Vertex actually uses, which is not one rule.
 *
 * `global` is served from the bare host, `eu`/`us` are *regional endpoints* on a `.rep.` subdomain,
 * and everything else is a location prefix. Guessing `{region}-aiplatform` for all three produces a
 * host that does not resolve for exactly the two regions Google's own docs steer multi-region users
 * toward.
 */
internal fun vertexHost(location: String): String = when (location) {
    "global" -> "aiplatform.googleapis.com"
    "eu", "us" -> "aiplatform.$location.rep.googleapis.com"
    else -> "$location-aiplatform.googleapis.com"
}

/**
 * The `publishers/google` base every Vertex media model shares.
 *
 * **`v1`, per Google's own reference for each surface reached through it** — not `v1beta1`, which is
 * what this carried while its justification was "matching the reference". Both surfaces exist, so the
 * old path resolved and nothing failed loudly; it was simply not the documented call, and the port had
 * no authority for it beyond a secondary source. Checked 2026-09-01:
 *
 * - Veo submit and poll — `POST .../v1/projects/{p}/locations/{l}/publishers/google/models/{m}:predictLongRunning`
 *   and `:fetchPredictOperation`
 *   ([predictLongRunning](https://docs.cloud.google.com/vertex-ai/docs/reference/rest/v1/projects.locations.publishers.models/predictLongRunning))
 * - Embeddings `:predict` — same `v1` shape
 *   ([text embeddings API](https://docs.cloud.google.com/vertex-ai/generative-ai/docs/model-reference/text-embeddings-api))
 * - Embeddings `:embedContent` — documented at `v1` and additionally exposed under `v1beta1`
 *   ([embedContent](https://docs.cloud.google.com/vertex-ai/generative-ai/docs/reference/rest/v1/projects.locations.publishers.models/embedContent))
 * - `generateContent`, which the reused image, Gemini-TTS and Gemini-transcription models all ride
 *   ([generateContent](https://docs.cloud.google.com/vertex-ai/generative-ai/docs/reference/rest/v1/projects.locations.publishers.models/generateContent))
 *
 * That last one is why this was worth correcting rather than leaving alone: [VertexProvider]'s own
 * `languageModel` already built `generateContent` on `v1`, so one package was reaching a single Vertex
 * surface through two different version segments depending on which model object a caller happened to
 * hold. Now there is one answer.
 *
 * The two media surfaces that do NOT come through here keep their own hosts and versions, both
 * confirmed: Cloud Text-to-Speech at `texttospeech.googleapis.com/v1`, and Cloud Speech-to-Text at
 * `speech.googleapis.com/v2` — a genuinely different API, not a different version of this one.
 */
internal fun vertexPublisherBaseUrl(projectId: String, location: String): String =
    "https://${vertexHost(location)}/v1/projects/$projectId/locations/$location/publishers/google"

/**
 * Options for a google-package model reused on Vertex: `google-vertex` re-filed into `google`.
 *
 * The reused models ([com.sabreware.aide.aisdk.providers.google.GoogleLanguageModel] and its media
 * siblings) read the `google` namespace, because the model is the same and only the host changed — the
 * precedent [VertexProvider.languageModel] set. A caller holding a Vertex provider still files options
 * under the id it constructed, so the house rule (canonical namespace always read, the custom key wins
 * field-by-field) is applied HERE, before delegation, rather than teaching every google model a second
 * namespace.
 */
internal fun ProviderOptions?.withVertexRefiled(): ProviderOptions? {
    if (this == null || this[VERTEX_PROVIDER_ID] == null) return this
    return this - VERTEX_PROVIDER_ID +
        (GOOGLE_PROVIDER_ID to mergedFor(GOOGLE_PROVIDER_ID, VERTEX_PROVIDER_ID))
}

/**
 * The merged vendor options an own-wire Vertex model reads: canonical `google` under `google-vertex`.
 *
 * Null when NEITHER namespace is present, which is the distinction a request builder needs — see
 * [mergedForOrNull].
 */
internal fun ProviderOptions?.vertexVendorOptions(): JsonObject? =
    mergedForOrNull(GOOGLE_PROVIDER_ID, VERTEX_PROVIDER_ID)

/**
 * Google duration strings — `"1.200s"` — parsed to seconds.
 *
 * Both Speech-to-Text word offsets and billed durations arrive in this shape; a parse failure is
 * treated as absent rather than zero, because a segment at 0.0s is a claim, not a shrug.
 */
internal fun parseVertexDurationSeconds(value: String?): Double? =
    value?.removeSuffix("s")?.toDoubleOrNull()?.takeIf { it.isFinite() }
