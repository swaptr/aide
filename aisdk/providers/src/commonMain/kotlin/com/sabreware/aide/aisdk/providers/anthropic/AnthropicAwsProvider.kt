package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.ProviderFiles
import com.sabreware.aide.aisdk.ProviderSkills
import com.sabreware.aide.aisdk.util.AwsCredentials
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.SigV4
import io.ktor.client.HttpClient

/** The provider id. Payloads still file under `anthropic`: the host changed, the model did not. */
public const val ANTHROPIC_AWS_PROVIDER_ID: String = "anthropic-aws"

/**
 * The Claude Platform on AWS: Anthropic's own Messages API, hosted in a customer's AWS account.
 *
 * Not Bedrock. Bedrock is AWS's abstraction over many vendors — its own URL shape, its own binary event
 * stream, its own `anthropic_version` — whereas this is the Anthropic API verbatim, reached through an
 * AWS endpoint and paid for through an AWS bill. So [AnthropicLanguageModel] is used unchanged, with SSE
 * and the direct API's own request shape; only authentication differs.
 *
 * Two ways to authenticate, and they are alternatives rather than a fallback chain:
 *
 * - **[apiKey]** — an AWS-provisioned key, sent as `x-api-key`. Nothing is signed.
 * - **[credentials]** — SigV4 over the request body, against the `aws-external-anthropic` service. The
 *   lambda is called per request because STS credentials expire; a provider holding a snapshot works
 *   until the session token rotates and then fails as a 403 that reads like a permissions problem.
 *
 * Everything is a constructor parameter, where the reference reads the environment. This module targets
 * platforms that have no environment to read — and a library that silently picks up `AWS_ACCESS_KEY_ID`
 * is a library that signs requests with credentials its caller did not choose.
 */
public class AnthropicAwsProvider(
    client: HttpClient,
    private val region: String,
    /**
     * The Anthropic workspace this AWS account bills to, sent on every request. Required: the endpoint
     * rejects a request without it, and the failure names the header rather than the account.
     */
    private val workspaceId: String,
    private val apiKey: String? = null,
    private val credentials: (() -> AwsCredentials)? = null,
    /** Epoch millis. A SigV4 signature is valid only within a few minutes of it, so the clock is injected. */
    private val now: () -> Long = { 0L },
    private val baseUrl: String? = null,
    private val apiVersion: String = AnthropicProvider.DEFAULT_API_VERSION,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : Provider {

    init {
        require(apiKey != null || credentials != null) {
            "AnthropicAwsProvider needs either an AWS-provisioned apiKey or SigV4 credentials"
        }
    }

    override val providerId: String = ANTHROPIC_AWS_PROVIDER_ID

    private val http = ProviderHttp(client)

    private val resolvedBaseUrl: String =
        baseUrl?.trimEnd('/') ?: "https://$SERVICE.$region.api.aws/v1"

    override fun languageModel(modelId: String): LanguageModel = AnthropicLanguageModel(
        modelId = modelId,
        http = http,
        baseUrl = resolvedBaseUrl,
        headers = buildMap {
            put("anthropic-version", apiVersion)
            put("anthropic-workspace-id", workspaceId)
            apiKey?.let { put("x-api-key", it) }
            putAll(extraHeaders)
        },
        // SigV4 covers the body bytes and the header set, so the signature can only be computed once the
        // request is final — which is what the hook exists for.
        signRequest = credentials?.let { provider ->
            { url, payload, headers ->
                SigV4.signedHeaders(
                    method = "POST",
                    url = url,
                    headers = headers + mapOf("content-type" to "application/json"),
                    payload = payload.encodeToByteArray(),
                    credentials = provider(),
                    region = region,
                    service = SERVICE,
                    timestampMillis = now(),
                )
            }
        },
    )

    /**
     * The Files API on the AWS host — key-authenticated only. A SigV4 deployment cannot serve it yet:
     * the signing hook covers the JSON body of a language-model call, and a multipart upload's exact
     * bytes are not reproducible outside the transport, so signing them here would produce a signature
     * over a body Ktor then encodes differently — a 403 that reads like a permissions problem.
     */
    public fun files(): ProviderFiles {
        requireApiKeyFor("files")
        return AnthropicFiles(http = http, baseUrl = resolvedBaseUrl, headers = keyHeaders())
    }

    /** The Skills API on the AWS host — key-authenticated only, for the same reason as [files]. */
    public fun skills(): ProviderSkills {
        requireApiKeyFor("skills")
        return AnthropicSkills(http = http, baseUrl = resolvedBaseUrl, headers = keyHeaders())
    }

    private fun requireApiKeyFor(surface: String) {
        requireNotNull(apiKey) {
            "The AWS-hosted $surface API needs the x-api-key auth mode; " +
                "SigV4 covers only language-model calls here"
        }
    }

    private fun keyHeaders(): Map<String, String> = buildMap {
        put("anthropic-version", apiVersion)
        put("anthropic-workspace-id", workspaceId)
        apiKey?.let { put("x-api-key", it) }
        putAll(extraHeaders)
    }

    private companion object {
        const val SERVICE = "aws-external-anthropic"
    }
}
