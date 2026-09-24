package com.sabreware.aide.aisdk.providers.bytedance

import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.VideoModel
import com.sabreware.aide.aisdk.util.ProviderErrorStructure
import com.sabreware.aide.aisdk.util.ProviderHttp
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The provider id, and the namespace ByteDance options and metadata file under. */
public const val BYTEDANCE_PROVIDER_ID: String = "bytedance"

/**
 * ByteDance ModelArk (BytePlus) — the Seedance video models.
 *
 * **Video only, deliberately.** ByteDance's chat endpoint is OpenAI-compatible and its image endpoint
 * is `/images/generations`, so both are served by `Vendors.bytedance` in the compat package — this
 * class exists because video is the one modality with its own task wire
 * (`/contents/generations/tasks`, submit-then-poll), which no compat shape covers. Binding chat or
 * images here as well would be a second path to the same endpoints for a caller to pick wrongly.
 */
public class BytedanceProvider(
    client: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : Provider {

    override val providerId: String = BYTEDANCE_PROVIDER_ID

    private val http = ProviderHttp(client).withErrorStructure(BytedanceErrors)

    override fun videoModel(modelId: String): VideoModel = BytedanceVideoModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        headers = mapOf("Authorization" to "Bearer $apiKey") + extraHeaders,
    )

    public companion object {
        /** ModelArk's Asia-Pacific endpoint — the one the reference defaults to. */
        public const val DEFAULT_BASE_URL: String = "https://ark.ap-southeast.bytepluses.com/api/v3"
    }
}

/** ModelArk wraps errors as `{"error": {"message": …}}`, with a flat `message` fallback. */
internal val BytedanceErrors: ProviderErrorStructure = ProviderErrorStructure(
    extractMessage = { body ->
        val obj = body as? JsonObject
        ((obj?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.content
            ?: (obj?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content
    },
)
