package com.sabreware.aide.aisdk.providers.bedrock

import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.providers.openai.OpenAIResponsesLanguageModel
import com.sabreware.aide.aisdk.providers.openai.ResponsesQuirks
import com.sabreware.aide.aisdk.providers.openaicompatible.OpenAICompatibleProvider
import com.sabreware.aide.aisdk.util.AwsCredentials
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import io.ktor.client.HttpClient

/** The provider id, and the namespace Mantle options and metadata file under. */
public const val BEDROCK_MANTLE_PROVIDER_ID: String = "bedrock-mantle"

/** Mantle's SigV4 scope: a service of its own, not `bedrock`'s — its IAM action is `bedrock-mantle:CreateInference`. */
public const val BEDROCK_MANTLE_SIGNING_SERVICE: String = "bedrock-mantle"

/** Mantle's regional endpoint. Public: a host overriding the base needs the default to fall back to. */
public fun bedrockMantleBaseUrl(region: String): String = "https://bedrock-mantle.$region.api.aws/v1"

/**
 * How a Mantle request authenticates.
 *
 * Bedrock accepts either. The reference reads an API key first and falls back to SigV4 when it is
 * absent or blank; here the choice is a constructor argument, so a provider built with a key that turns
 * out to be empty fails at construction rather than quietly signing as whichever AWS credentials the
 * environment happened to hold.
 */
public sealed interface BedrockMantleAuth {

    /** An Amazon Bedrock API key, sent as `Authorization: Bearer` — what the OpenAI SDKs are pointed at. */
    public class ApiKey(public val apiKey: String) : BedrockMantleAuth {
        init {
            require(apiKey.isNotBlank()) {
                "A Bedrock API key must not be blank; use BedrockMantleAuth.SigV4 to sign with AWS credentials."
            }
        }
    }

    /**
     * AWS credentials; every request is signed in the [BEDROCK_MANTLE_SIGNING_SERVICE] scope.
     *
     * [credentials] and [now] are lambdas for the reasons [BedrockProvider] gives: STS credentials
     * rotate, and a signature is valid only within minutes of its timestamp, so the clock is the
     * caller's and a test can pin it.
     */
    public class SigV4(
        public val credentials: () -> AwsCredentials,
        public val now: () -> Long,
    ) : BedrockMantleAuth
}

/**
 * Amazon Bedrock Mantle: the OpenAI-compatible endpoint, `bedrock-mantle.{region}.api.aws/v1`, for the
 * models Bedrock serves ONLY through it — OpenAI's open-weight `openai.gpt-oss-*` family.
 *
 * What Mantle turned out to be, read off AWS's own pages on 2026-09-02: a plain OpenAI wire.
 * `/chat/completions`, `/responses` and `/models`, authenticated with a Bedrock API key as a bearer
 * token or with AWS credentials as SigV4 in a service scope of its own. Nothing here is Bedrock's
 * native `InvokeModel` or Converse shape, which is why this is a wrapper over
 * [OpenAICompatibleProvider] rather than a sibling of [BedrockProvider]: a different host, different
 * auth and the vendor's own wire, sharing nothing with the native path but the signer.
 *
 * [languageModel] is Chat Completions — the broadest model coverage on Mantle, and the reference's
 * default. [responsesLanguageModel] is the Responses API, which the safeguard models do not serve.
 * Embeddings and images are not offered on this endpoint and resolve to null, the way this module says
 * "absent" — the reference throws `NoSuchModelError` for both, the stub its own `ProviderV4` forces.
 *
 * Both models keep the OpenAI-compatible engine's defaults: gpt-oss ids match no OpenAI family rule,
 * so they are treated as ordinary models — samplers pass through — exactly as the reference's
 * `OpenAIChatLanguageModel` classifies them. Options and metadata file under [BEDROCK_MANTLE_PROVIDER_ID].
 */
public class BedrockMantleProvider(
    client: HttpClient,
    region: String,
    auth: BedrockMantleAuth,
    baseUrl: String = bedrockMantleBaseUrl(region),
    extraHeaders: Map<String, String> = emptyMap(),
) : Provider {

    override val providerId: String = BEDROCK_MANTLE_PROVIDER_ID

    private val endpoint = baseUrl.trimEnd('/')

    /** The bearer token when there is one; null means every request goes through the signing client. */
    private val apiKey: String? = (auth as? BedrockMantleAuth.ApiKey)?.apiKey

    private val transport: HttpClient = when (auth) {
        is BedrockMantleAuth.ApiKey -> client
        is BedrockMantleAuth.SigV4 -> client.signingRequestsAs(
            service = BEDROCK_MANTLE_SIGNING_SERVICE,
            region = region,
            credentials = auth.credentials,
            now = auth.now,
        )
    }

    private val headers: Map<String, String> = combineHeaders(
        apiKey?.let { mapOf("Authorization" to "Bearer $it") },
        extraHeaders,
    )

    private val compat = OpenAICompatibleProvider(
        client = transport,
        providerId = BEDROCK_MANTLE_PROVIDER_ID,
        baseUrl = endpoint,
        apiKey = apiKey,
        extraHeaders = extraHeaders,
    )

    /** Chat Completions, the endpoint every Mantle model serves. */
    override fun languageModel(modelId: String): LanguageModel = requireNotNull(compat.languageModel(modelId))

    /**
     * The Responses API — stateful turns and background inference, on the models that serve it.
     *
     * Reported as `bedrock-mantle.responses` and filed under `bedrock-mantle`, the split Azure's
     * Responses model already draws: one namespace for the vendor, a distinct id for the wire.
     */
    public fun responsesLanguageModel(modelId: String): LanguageModel = OpenAIResponsesLanguageModel(
        modelId = modelId,
        http = ProviderHttp(transport),
        headers = headers,
        provider = "$BEDROCK_MANTLE_PROVIDER_ID.responses",
        namespace = BEDROCK_MANTLE_PROVIDER_ID,
        endpointUrl = "$endpoint/responses",
        // Mantle's Responses endpoint rejects the `web_search_call.action.sources` include (upstream
        // `0de8886`), so the request builder must not ask for it.
        quirks = ResponsesQuirks(supportsWebSearchSourcesInclude = false),
    )
}
