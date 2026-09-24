package com.sabreware.aide.aisdk.providers.vertex

import com.sabreware.aide.aisdk.EmbeddingModel
import com.sabreware.aide.aisdk.ImageModel
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.SpeechModel
import com.sabreware.aide.aisdk.TranscriptionModel
import com.sabreware.aide.aisdk.VideoModel
import com.sabreware.aide.aisdk.providers.google.GoogleImageModel
import com.sabreware.aide.aisdk.providers.google.GoogleLanguageModel
import com.sabreware.aide.aisdk.providers.google.GoogleSpeechModel
import com.sabreware.aide.aisdk.providers.google.ToolResultDownloads
import com.sabreware.aide.aisdk.util.ProviderHttp
import io.ktor.client.HttpClient

/** The provider id. Gemini payloads still file under `google` — the model is the same, the host is not. */
public const val VERTEX_PROVIDER_ID: String = "google-vertex"

/**
 * Google Vertex AI.
 *
 * **The RSA problem, dissolved rather than solved.** Vertex was flagged at planning time as needing
 * RSA-SHA256 for service-account JWT signing, which okio does not provide and which would have forced a
 * crypto dependency or a `jvmShared` fork. It does not: Vertex authenticates with an OAuth2 *bearer
 * token*, and how a host obtains one — Application Default Credentials, the GCE metadata server, `gcloud
 * auth`, or a signed service-account assertion — is a platform concern with a different right answer on
 * every platform.
 *
 * So this takes [accessToken] and signs nothing. That is not a shortcut: minting a service-account JWT
 * inside a chat library would mean holding a private key in a component that has no business holding one,
 * and would still be the wrong mechanism on GCE, where the metadata server hands out tokens without a key
 * at all.
 *
 * [accessToken] is `suspend` and called per request because Vertex tokens expire in about an hour; a
 * provider holding a snapshot works until it rotates and then fails as a 401 that reads like a bad key.
 *
 * Like Bedrock, Vertex is a transport hosting several vendors under `publishers/`, so the provider routes
 * by publisher: Gemini reuses [GoogleLanguageModel] against a Vertex path, Claude uses
 * [VertexAnthropicLanguageModel]. Both reuse the same builders and mappers as their direct APIs, so a
 * signature behaves identically whichever host a conversation was held against.
 */
public class VertexProvider(
    client: HttpClient,
    private val projectId: String,
    private val location: String,
    private val accessToken: suspend () -> String,
    /**
     * The per-file cap on the tool-result files Gemini fetches before a request — 7 MiB unless the host
     * says otherwise. Vertex function responses take inline data only, so a URL a tool returned is
     * downloaded here rather than handed to the model as a name.
     */
    private val toolResultDownloads: ToolResultDownloads = ToolResultDownloads(),
) : Provider {

    override val providerId: String = VERTEX_PROVIDER_ID

    private val http = ProviderHttp(client)

    /** Every request re-resolves the token — Vertex tokens rotate hourly; see the class KDoc. */
    private val authHeaders: suspend () -> Map<String, String> =
        { mapOf("Authorization" to "Bearer ${accessToken()}") }

    /**
     * The publisher base the media models share, with its regional-host rule ([vertexHost]).
     *
     * `v1`, matching both Google's documentation for each surface and the language-model paths below —
     * see [vertexPublisherBaseUrl] for the per-surface citations.
     */
    private val mediaBaseUrl: String get() = vertexPublisherBaseUrl(projectId, location)

    override fun languageModel(modelId: String): LanguageModel {
        // Vertex hosts several vendors under `publishers/`, and each keeps its OWN body format. Routing
        // by publisher is the whole difference between the two models this provider serves.
        if (modelId.startsWith("claude")) {
            return VertexAnthropicLanguageModel(
                modelId = modelId,
                http = http,
                projectId = projectId,
                location = location,
                accessToken = accessToken,
            )
        }
        return GoogleLanguageModel(
            modelId = modelId,
            http = http,
            // The publisher segment is part of the base so the model's own path builder still applies.
            baseUrl = "https://$location-aiplatform.googleapis.com/v1/projects/$projectId" +
                "/locations/$location/publishers/google",
            headers = { mapOf("Authorization" to "Bearer ${accessToken()}") },
            // Vertex rejects `includeServerSideToolInvocations`, which the Gemini API needs in order to
            // report the built-in tools it ran at all.
            isVertex = true,
            toolResultDownloads = toolResultDownloads,
        )
    }

    /** Vertex embeddings — an OWN wire (`:predict` / `:embedContent`), not the Gemini API's. */
    override fun embeddingModel(modelId: String): EmbeddingModel =
        VertexEmbeddingModel(modelId, http, mediaBaseUrl, authHeaders)

    /** Gemini image generation, reused from the google package — see [VertexGeminiImageModel]. */
    override fun imageModel(modelId: String): ImageModel = VertexGeminiImageModel(
        GoogleImageModel(modelId, http, mediaBaseUrl, authHeaders),
    )

    /**
     * `chirp*` ids are Cloud Text-to-Speech ([VertexCloudTtsSpeechModel], its own host and wire);
     * everything else is Gemini TTS, reused from the google package.
     */
    override fun speechModel(modelId: String): SpeechModel =
        if (modelId.startsWith("chirp")) {
            VertexCloudTtsSpeechModel(modelId, http, authHeaders)
        } else {
            VertexGeminiSpeechModel(GoogleSpeechModel(modelId, http, mediaBaseUrl, authHeaders))
        }

    /**
     * `gemini*` ids transcribe through Vertex `generateContent` ([VertexGeminiTranscriptionModel] — a
     * different wire from the Gemini API's `/interactions`); everything else (Chirp, telephony) routes
     * to Cloud Speech-to-Text ([VertexTranscriptionModel]).
     */
    override fun transcriptionModel(modelId: String): TranscriptionModel =
        if (modelId.startsWith("gemini")) {
            VertexGeminiTranscriptionModel(modelId, http, mediaBaseUrl, authHeaders)
        } else {
            VertexTranscriptionModel(modelId, http, projectId, location, authHeaders)
        }

    /** Veo on Vertex — same family as the Gemini API's, different operation protocol. */
    override fun videoModel(modelId: String): VideoModel =
        VertexVideoModel(modelId, http, mediaBaseUrl, authHeaders)
}
