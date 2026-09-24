package com.sabreware.aide.aisdk.providers.hume

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.SpeechModel
import com.sabreware.aide.aisdk.SpeechResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.forProvider
import com.sabreware.aide.aisdk.providers.options.optArray
import com.sabreware.aide.aisdk.providers.options.optDouble
import com.sabreware.aide.aisdk.providers.options.optElement
import com.sabreware.aide.aisdk.providers.options.optObject
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The provider id, and the namespace Hume payloads file under. */
public const val HUME_PROVIDER_ID: String = "hume"

/**
 * Hume text-to-speech.
 *
 * Its request is shaped around *utterances* rather than a single text field, and the voice is an object
 * carrying both an id and the library that id belongs to. Auth is `X-Hume-Api-Key`.
 */
public class HumeProvider internal constructor(
    private val http: ProviderHttp,
    apiKey: String,
    baseUrl: String,
    extraHeaders: Map<String, String>,
) : Provider {

    public constructor(
        client: HttpClient,
        apiKey: String,
        baseUrl: String = DEFAULT_BASE_URL,
        headers: Map<String, String> = emptyMap(),
    ) : this(ProviderHttp(client), apiKey, baseUrl, headers)

    override val providerId: String = HUME_PROVIDER_ID

    private val endpoint = baseUrl.trimEnd('/')

    private val headers = combineHeaders(mapOf("X-Hume-Api-Key" to apiKey), extraHeaders)

    override fun speechModel(modelId: String): SpeechModel = HumeSpeechModel(
        modelId = modelId,
        http = http,
        baseUrl = endpoint,
        headers = headers,
    )

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.hume.ai"

        /** Hume's own documented default voice id. */
        public const val DEFAULT_VOICE_ID: String = "d8ab67c6-953d-4bd8-9370-8fa53a0f1453"

        /** The container types Hume returns. */
        public val SUPPORTED_FORMATS: Set<String> = setOf("mp3", "pcm", "wav")
    }
}

internal class HumeSpeechModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
) : SpeechModel {

    override val provider: String = HUME_PROVIDER_ID

    override suspend fun doGenerate(options: SpeechCallOptions): SpeechResult {
        val warnings = mutableListOf<Warning>()
        val vendor = options.providerOptions?.forProvider(HUME_PROVIDER_ID)

        if (options.language != null) {
            warnings += Warning.Unsupported(
                feature = "language",
                details = "Hume speech models do not support language selection. " +
                    "Language parameter \"${options.language}\" was ignored.",
            )
        }

        val format = options.outputFormat?.takeIf { it in HumeProvider.SUPPORTED_FORMATS }
            ?: run {
                options.outputFormat?.let {
                    warnings += Warning.Unsupported(
                        feature = "outputFormat",
                        details = "Unsupported output format: $it. Using mp3 instead.",
                    )
                }
                DEFAULT_FORMAT
            }

        val body = buildJsonObject {
            // Utterances rather than a text field — Hume's request is built around them, and
            // `instructions` is the one vendor here that HAS a place for delivery direction.
            putJsonArray("utterances") {
                add(
                    buildJsonObject {
                        put("text", options.text)
                        // Hume accepts a per-utterance rate; it is not a knob this vendor lacks.
                        options.speed?.let { put("speed", it) }
                        options.instructions?.let { put("description", it) }
                        putJsonObject("voice") {
                            put("id", options.voice ?: HumeProvider.DEFAULT_VOICE_ID)
                            // Which library the id belongs to. Omitting it resolves the id against the
                            // caller's custom voices, so a built-in id is reported as not found.
                            put("provider", "HUME_AI")
                        }
                    },
                )
            }
            putJsonObject("format") { put("type", format) }
            vendor?.optObject("context")?.let { putContext(it) }
        }

        val result = http.postBytesForBytes(
            url = "$baseUrl/v0/tts/file",
            body = body,
            headers = combineHeaders(headers, options.headers),
        )
        if (result.value.isEmpty()) throw NoContentGeneratedError("Hume returned no audio.")

        return SpeechResult(
            audio = BinaryData.Bytes(result.value),
            warnings = warnings,
            request = result.requestInfo(),
            response = result.modalityResponse(modelId = modelId),
        )
    }

    /**
     * Prior utterances Hume continues the prosody from, either by generation id or verbatim.
     *
     * The union is one-or-the-other on the wire: a `context` carrying both is rejected, which is why the
     * generation id short-circuits rather than merging.
     */
    private fun JsonObjectBuilder.putContext(context: JsonObject) {
        context.optString("generationId")?.let { id ->
            putJsonObject("context") { put("generation_id", id) }
            return
        }
        val utterances = context.optArray("utterances") ?: return
        putJsonObject("context") {
            putJsonArray("utterances") {
                utterances.forEach { entry ->
                    val utterance = entry.jsonObject
                    add(
                        buildJsonObject {
                            utterance.optString("text")?.let { put("text", it) }
                            utterance.optString("description")?.let { put("description", it) }
                            utterance.optDouble("speed")?.let { put("speed", it) }
                            utterance.optDouble("trailingSilence")?.let { put("trailing_silence", it) }
                            // Already in Hume's own shape (`id`/`name` plus `provider`), so it is passed
                            // through rather than rebuilt.
                            utterance.optElement("voice")?.let { put("voice", it) }
                        },
                    )
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_FORMAT = "mp3"
    }
}
