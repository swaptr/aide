package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.BatchLanguageModel
import com.sabreware.aide.aisdk.EmbeddingModel
import com.sabreware.aide.aisdk.ImageModel
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.RealtimeModel
import com.sabreware.aide.aisdk.SpeechModel
import com.sabreware.aide.aisdk.SpeechTranslationModel
import com.sabreware.aide.aisdk.TranscriptionModel
import com.sabreware.aide.aisdk.VideoModel
import com.sabreware.aide.aisdk.providers.google.interactions.GoogleInteractionsLanguageModel
import com.sabreware.aide.aisdk.providers.google.interactions.GoogleInteractionsTarget
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderSocket
import io.ktor.client.HttpClient

/**
 * Google's Gemini API.
 *
 * Authentication is the `x-goog-api-key` header rather than a bearer token. The key can also go in the
 * query string, but a URL is logged in more places than a header is, so the header is the default here.
 */
/** The Gemini API base. Public: a host overriding the endpoint needs the default to fall back to. */
public const val GOOGLE_DEFAULT_BASE_URL: String = "https://generativelanguage.googleapis.com/v1beta"

public class GoogleProvider(
    private val client: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = GOOGLE_DEFAULT_BASE_URL,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : Provider {

    override val providerId: String = GOOGLE_PROVIDER_ID

    private val http = ProviderHttp(client)

    private val requestHeaders: suspend () -> Map<String, String> = {
        buildMap {
            put("x-goog-api-key", apiKey)
            putAll(extraHeaders)
        }
    }

    override fun languageModel(modelId: String): LanguageModel = GoogleLanguageModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        headers = requestHeaders,
    )

    /**
     * The same model on the Interactions API — a separate surface from `generateContent` with `steps`,
     * a stateful mode keyed by interaction id, and background runs for the deep-research agents. It is
     * not a drop-in for [languageModel]: options file under `google` as usual, but the wire, the tool
     * table and the polling story are their own, which is why it lives in its own package.
     */
    public fun interactions(modelId: String): LanguageModel = interactions(GoogleInteractionsTarget.Model(modelId))

    /** A Google-hosted agent (see `GoogleInteractionsAgents`) on the Interactions API. */
    public fun interactionsAgent(name: String): LanguageModel = interactions(GoogleInteractionsTarget.Agent(name))

    /** Any Interactions target — a model, a Google agent or a managed one. */
    public fun interactions(target: GoogleInteractionsTarget): LanguageModel = GoogleInteractionsLanguageModel(
        target = target,
        http = http,
        client = client,
        baseUrl = baseUrl.trimEnd('/'),
        headers = requestHeaders,
    )

    /**
     * The same model on Gemini's batch API — many requests submitted once, collected later, at half
     * price. See [BatchLanguageModel] for the lifecycle.
     */
    public fun batchLanguageModel(modelId: String): BatchLanguageModel = GoogleBatchModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        headers = requestHeaders,
    )

    /**
     * Gemini Live, as a realtime model.
     *
     * Not a [Provider] modality: [RealtimeModel] mints a credential and translates frames rather than
     * owning a transport, so it has no slot on the contract every other modality shares. Reached
     * directly, by the caller that is going to open the socket. The key travels in the token request's
     * query string, as on the other Live sockets — and one model serves one session, because Gemini's
     * frames carry no ids and the model has to number them.
     */
    public fun realtimeModel(modelId: String): RealtimeModel = GoogleRealtimeModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        apiKey = apiKey,
        extraHeaders = extraHeaders,
    )

    override fun embeddingModel(modelId: String): EmbeddingModel = GoogleEmbeddingModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        headers = requestHeaders,
    )

    override fun imageModel(modelId: String): ImageModel = GoogleImageModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        headers = requestHeaders,
    )

    override fun speechModel(modelId: String): SpeechModel = GoogleSpeechModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        headers = requestHeaders,
    )

    override fun transcriptionModel(modelId: String): TranscriptionModel = GoogleTranscriptionModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        headers = requestHeaders,
        // The live half is a socket, and the Live handshake takes the key in the URL rather than in a
        // header — see GoogleTranscriptionModel.doStream.
        socket = ProviderSocket(client),
        apiKey = apiKey,
    )

    override fun videoModel(modelId: String): VideoModel = GoogleVideoModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        headers = requestHeaders,
    )

    /**
     * Gemini Live translation, which is a WebSocket protocol rather than a REST call — hence the socket
     * built here from the same injected client, and hence the key travelling in the URL rather than in
     * the header the rest of this provider authenticates with.
     */
    override fun speechTranslationModel(modelId: String): SpeechTranslationModel =
        GoogleSpeechTranslationModel(
            modelId = modelId,
            socket = ProviderSocket(client),
            baseUrl = baseUrl,
            apiKey = apiKey,
            extraHeaders = extraHeaders,
        )
}
