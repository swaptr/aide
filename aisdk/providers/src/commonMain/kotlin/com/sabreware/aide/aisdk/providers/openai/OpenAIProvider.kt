package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.BatchLanguageModel
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.ProviderFiles
import com.sabreware.aide.aisdk.ProviderSkills
import com.sabreware.aide.aisdk.RealtimeModel
import com.sabreware.aide.aisdk.SpeechTranslationModel
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderSocket
import io.ktor.client.HttpClient

/**
 * OpenAI, on the Responses API.
 *
 * `languageModel` resolves to [OpenAIResponsesLanguageModel] rather than to the OpenAI-compatible chat
 * model, which matches `@ai-sdk/openai` and is the only one of the two that can carry a reasoning turn
 * across a tool round. A caller that specifically wants Chat Completions — a gateway that serves only
 * that endpoint — goes through the OpenAI-compatible provider, which is what that package is for.
 *
 * Beside the language model: the same model on the Batch API ([batchLanguageModel]), the Files and
 * Skills stores that mint the ids a prompt references ([files], [skills]), and realtime speech
 * translation ([speechTranslationModel]). Each is bound because it has a ported, fixture-tested path
 * behind it. An embedding, image, speech or transcription model this provider has not been verified
 * against stays unbound — advertising one would be a capability with no tested path, which is the
 * failure mode the repo's own ratchet names: a capability a target lacks is bound nowhere.
 */
public class OpenAIProvider(
    private val client: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = OpenAIResponsesLanguageModel.DEFAULT_BASE_URL,
    /** The organization and project headers, and anything a gateway in front of OpenAI requires. */
    private val extraHeaders: Map<String, String> = emptyMap(),
) : Provider {

    override val providerId: String = OPENAI_PROVIDER_ID

    private val http = ProviderHttp(client)

    private val headers: Map<String, String>
        get() = buildMap {
            put("Authorization", "Bearer $apiKey")
            putAll(extraHeaders)
        }

    override fun languageModel(modelId: String): LanguageModel = OpenAIResponsesLanguageModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        headers = headers,
    )

    /**
     * The same model, on the Batch API — many requests uploaded once, run within a day at half price,
     * collected later. See [BatchLanguageModel] for the lifecycle.
     */
    public fun batchLanguageModel(modelId: String): BatchLanguageModel = OpenAIResponsesBatchModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        headers = headers,
    )

    /** The Files API — uploads that mint the ids a [com.sabreware.aide.aisdk.FileData.Reference] carries. */
    public fun files(): ProviderFiles = OpenAIFiles(
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        headers = headers,
    )

    /** The Skills API — uploads a skill directory the model's tools can load. */
    public fun skills(): ProviderSkills = OpenAISkills(
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        headers = headers,
    )

    /**
     * Realtime speech translation, which is a WebSocket protocol rather than a REST call — hence the
     * socket built here from the same injected client. The bearer token is moved into a subprotocol by
     * the model itself; see [openAIRealtimeConnection].
     */
    override fun speechTranslationModel(modelId: String): SpeechTranslationModel =
        OpenAISpeechTranslationModel(
            modelId = modelId,
            socket = ProviderSocket(client),
            baseUrl = baseUrl.trimEnd('/'),
            headers = headers,
        )

    /**
     * The realtime (speech-in, speech-out) model, on whichever of OpenAI's two protocols serves
     * [modelId] — see [resolveOpenAIRealtimeApi] for the rule and [api] to override it.
     *
     * A Realtime model mints the client secret here, where the key is, and translates frames for a
     * socket the CALLER opens — see [RealtimeModel] for why the contract splits. A Live model
     * ([OpenAILiveModel]) has no client secret to mint: its socket is server-owned and authenticated
     * with the key itself, which [RealtimeModel.serverWebSocketConfig] hands over.
     */
    public fun realtimeModel(modelId: String, api: OpenAIRealtimeApi? = null): RealtimeModel =
        when (resolveOpenAIRealtimeApi(modelId, api)) {
            OpenAIRealtimeApi.Live -> OpenAILiveModel(
                modelId = modelId,
                baseUrl = baseUrl.trimEnd('/'),
                headers = headers,
            )
            OpenAIRealtimeApi.Realtime -> OpenAIRealtimeModel(
                modelId = modelId,
                http = http,
                baseUrl = baseUrl.trimEnd('/'),
                headers = headers,
            )
        }
}
