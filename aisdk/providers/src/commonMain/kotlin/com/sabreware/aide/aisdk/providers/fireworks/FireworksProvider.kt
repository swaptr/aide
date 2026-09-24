package com.sabreware.aide.aisdk.providers.fireworks

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageFile
import com.sabreware.aide.aisdk.ImageModel
import com.sabreware.aide.aisdk.ImageResult
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.JobStatus
import com.sabreware.aide.aisdk.util.PollPolicy
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.pollUntilDone
import io.ktor.client.HttpClient
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The provider id, and the namespace Fireworks payloads file under. */
public const val FIREWORKS_PROVIDER_ID: String = "fireworks"

/**
 * Fireworks image generation, natively.
 *
 * This is the provider that replaces the compat `/images/generations` binding this port once
 * advertised and removed, because Fireworks has no such endpoint — every request 404'd. What it has is
 * THREE url families, chosen by model:
 *
 * - **workflows** (the FLUX defaults): `POST {base}/workflows/{model}/text_to_image`, answering with
 *   the raw image bytes.
 * - **workflows async** (the Kontext editing models): `POST {base}/workflows/{model}` answering
 *   `{request_id}`, then polling `POST {base}/workflows/{model}/get_result` until `Ready` hands back a
 *   delivery URL to download.
 * - **image_generation** (the legacy SDXL-era models): `POST {base}/image_generation/{model}`, binary
 *   again, and the only family that takes a pixel `size` rather than an aspect ratio.
 *
 * Which family a model belongs to is a vendor fact, not a guess — the table below is the reference's
 * own, and an unknown model id falls to the workflows default exactly as it does upstream.
 */
public class FireworksProvider(
    client: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val pollPolicy: PollPolicy = PollPolicy.Fast,
    private val elapsedMillis: () -> Long,
) : Provider {

    override val providerId: String = FIREWORKS_PROVIDER_ID

    private val http = ProviderHttp(client)

    override fun imageModel(modelId: String): ImageModel = FireworksImageModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        apiKey = apiKey,
        pollPolicy = pollPolicy,
        elapsedMillis = elapsedMillis,
    )

    public companion object {
        /** The same base every Fireworks endpoint family hangs off. */
        public const val DEFAULT_BASE_URL: String = "https://api.fireworks.ai/inference/v1"
    }
}

/** Which of the three endpoint families serves a model, and what the family can express. */
internal data class FireworksBackend(
    val urlFormat: FireworksUrlFormat,
    val supportsSize: Boolean = false,
    val supportsEditing: Boolean = false,
)

internal enum class FireworksUrlFormat { Workflows, WorkflowsAsync, ImageGeneration }

/** The reference's own model→family table (`fireworks-image-model.ts`), kept verbatim. */
internal val FIREWORKS_BACKENDS: Map<String, FireworksBackend> = buildMap {
    put("accounts/fireworks/models/flux-1-dev-fp8", FireworksBackend(FireworksUrlFormat.Workflows))
    put("accounts/fireworks/models/flux-1-schnell-fp8", FireworksBackend(FireworksUrlFormat.Workflows))
    put(
        "accounts/fireworks/models/flux-kontext-pro",
        FireworksBackend(FireworksUrlFormat.WorkflowsAsync, supportsEditing = true),
    )
    put(
        "accounts/fireworks/models/flux-kontext-max",
        FireworksBackend(FireworksUrlFormat.WorkflowsAsync, supportsEditing = true),
    )
    listOf(
        "accounts/fireworks/models/playground-v2-5-1024px-aesthetic",
        "accounts/fireworks/models/japanese-stable-diffusion-xl",
        "accounts/fireworks/models/playground-v2-1024px-aesthetic",
        "accounts/fireworks/models/stable-diffusion-xl-1024-v1-0",
        "accounts/fireworks/models/SSD-1B",
    ).forEach { put(it, FireworksBackend(FireworksUrlFormat.ImageGeneration, supportsSize = true)) }
}

/**
 * A file as Fireworks' `input_image` wants it: the URL untouched, or a `data:` URI.
 *
 * The `data:` prefix is REQUIRED here where Black Forest Labs rejects it — the same FLUX models behind
 * two vendors, with opposite envelope rules.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun ImageFile.toFireworksImage(): String = when (this) {
    is ImageFile.Url -> url
    is ImageFile.Data -> "data:$mediaType;base64," + when (val payload = data) {
        is BinaryData.Base64 -> payload.value
        is BinaryData.Bytes -> Base64.encode(payload.value)
    }
}

internal class FireworksImageModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val apiKey: String,
    private val pollPolicy: PollPolicy,
    private val elapsedMillis: () -> Long,
) : ImageModel {

    override val provider: String = FIREWORKS_PROVIDER_ID

    override suspend fun maxImagesPerCall(): Int = 1

    private val authHeaders get() = mapOf("Authorization" to "Bearer $apiKey")

    override suspend fun doGenerate(options: ImageCallOptions): ImageResult {
        val backend = FIREWORKS_BACKENDS[modelId] ?: FireworksBackend(FireworksUrlFormat.Workflows)
        val warnings = mutableListOf<Warning>()

        if (!backend.supportsSize && options.size != null) {
            warnings += Warning.Unsupported(
                "size",
                "This model does not support the `size` option. Use `aspectRatio` instead.",
            )
        }
        // supportsSize doubles as does-NOT-support-aspectRatio; the invariant holds for the whole
        // table, per the reference's own comment.
        if (backend.supportsSize && options.aspectRatio != null) {
            warnings += Warning.Unsupported(
                "aspectRatio",
                "This model does not support the `aspectRatio` option.",
            )
        }
        if ((options.files?.size ?: 0) > 1) {
            warnings += Warning.Other(
                "Fireworks only supports a single input image. Additional images are ignored.",
            )
        }
        if (options.mask != null) {
            warnings += Warning.Unsupported(
                "mask",
                "Fireworks Kontext models do not support explicit masks. " +
                    "Use the prompt to describe the areas to edit.",
            )
        }

        val size = options.size?.split("x")?.takeIf { it.size == 2 }
        val body = buildJsonObject {
            options.prompt?.let { put("prompt", it) }
            options.aspectRatio?.let { put("aspect_ratio", it) }
            options.seed?.let { put("seed", it) }
            put("samples", options.n)
            options.files?.firstOrNull()?.let { put("input_image", it.toFireworksImage()) }
            // Strings, not numbers: the reference sends the split halves un-parsed, and the endpoint
            // accepts them — matching the recorded wire beats tidying it.
            size?.let {
                put("width", it[0])
                put("height", it[1])
            }
            // An open schema: every vendor option rides through under its own name, exactly as the
            // reference's loose spread does.
            options.providerOptions?.get(FIREWORKS_PROVIDER_ID)?.forEach { (key, value) ->
                if (key !in POLL_KNOBS) put(key, value)
            }
        }
        val headers = combineHeaders(authHeaders, options.headers)

        return when (backend.urlFormat) {
            FireworksUrlFormat.ImageGeneration ->
                binaryResult(http.postBytesForBytes("$baseUrl/image_generation/$modelId", body, headers), warnings)
            FireworksUrlFormat.Workflows ->
                binaryResult(
                    http.postBytesForBytes("$baseUrl/workflows/$modelId/text_to_image", body, headers),
                    warnings,
                )
            FireworksUrlFormat.WorkflowsAsync -> asyncResult(body, headers, warnings)
        }
    }

    private fun binaryResult(
        response: com.sabreware.aide.aisdk.util.HttpResult<ByteArray>,
        warnings: List<Warning>,
    ): ImageResult {
        if (response.value.isEmpty()) throw NoContentGeneratedError("Fireworks returned no image bytes.")
        return ImageResult(
            images = listOf(BinaryData.Bytes(response.value)),
            warnings = warnings,
            request = response.requestInfo(),
            response = response.modalityResponse(modelId = modelId),
        )
    }

    /** The Kontext path: submit for a `request_id`, poll `get_result`, download the delivery URL. */
    private suspend fun asyncResult(
        body: kotlinx.serialization.json.JsonObject,
        headers: Map<String, String>,
        warnings: List<Warning>,
    ): ImageResult {
        val submitted = http.postJson("$baseUrl/workflows/$modelId", body, headers)
        val requestId = submitted.value.jsonObject["request_id"]?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("Fireworks returned no request_id for an async generation.")

        val pollUrl = "$baseUrl/workflows/$modelId/get_result"
        val imageUrl = pollUntilDone(policy = pollPolicy, elapsedMillis = elapsedMillis) {
            val poll = http.postJson(pollUrl, buildJsonObject { put("id", requestId) }, headers)
                .value.jsonObject
            when (val status = poll["status"]?.jsonPrimitive?.content) {
                "Ready" -> poll["result"]?.jsonObject?.get("sample")?.jsonPrimitive?.content
                    ?.let { JobStatus.Succeeded(it) }
                    ?: JobStatus.Failed("Fireworks poll response is Ready but missing result.sample")
                "Error", "Failed" ->
                    JobStatus.Failed("Fireworks image generation failed with status: $status")
                else -> JobStatus.InProgress()
            }
        }

        // The delivery URL comes from the provider's own response, and typically points at a CDN. The
        // key travels only when the URL stays on our configured origin — never to a CDN or an
        // attacker-named host the response could have smuggled in. `trustedOrigin` IS that rule, applied
        // per redirect hop rather than once up front, so naming the origin is the whole of the gate:
        // deciding it here as well left two copies to keep in agreement, and named the fetched URL as
        // its own authority.
        val image = http.getBytes(url = imageUrl, headers = headers, trustedOrigin = baseUrl)
        return ImageResult(
            images = listOf(BinaryData.Bytes(image.value)),
            warnings = warnings,
            response = image.modalityResponse(modelId = modelId, id = requestId),
        )
    }
}

/** Consumed by the transport's own schedule, never sent to the vendor. */
private val POLL_KNOBS = setOf("pollIntervalMillis", "pollTimeoutMillis")
