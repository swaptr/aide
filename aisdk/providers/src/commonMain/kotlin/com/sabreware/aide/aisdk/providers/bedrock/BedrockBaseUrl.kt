package com.sabreware.aide.aisdk.providers.bedrock

/** The service every chat, embedding and image call is addressed to. */
internal const val BEDROCK_RUNTIME_SERVICE: String = "bedrock-runtime"

/** The service reranking is addressed to — a different host, the same signing scope. */
internal const val BEDROCK_AGENT_RUNTIME_SERVICE: String = "bedrock-agent-runtime"

/**
 * The endpoint for a Bedrock [service] in [region], or [override] when the host supplied one.
 *
 * `https://{service}.{region}.amazonaws.com` is right for the commercial partitions only. China, the
 * US isolated regions, the EU sovereign cloud and the European isolated region each have their own DNS
 * suffix, and a request to the commercial suffix from one of them does not fail as a wrong region — it
 * fails to resolve. The table is the reference's; an unknown prefix keeps the commercial suffix, which
 * is also what GovCloud (`us-gov-*`) uses.
 *
 * The reference also reads `AWS_ENDPOINT_URL_BEDROCK_RUNTIME`, `AWS_ENDPOINT_URL_BEDROCK_AGENT_RUNTIME`
 * and `AWS_ENDPOINT_URL` from the process environment. commonMain has no environment, so those are the
 * host's to read and pass as [override] — [BedrockProvider] takes them as constructor arguments.
 */
internal fun bedrockBaseUrl(
    region: String,
    service: String = BEDROCK_RUNTIME_SERVICE,
    override: String? = null,
): String {
    override?.let { return it.trimEnd('/') }
    val dnsSuffix = AWS_PARTITION_DNS_SUFFIXES
        .firstOrNull { (regionPrefix, _) -> region.startsWith(regionPrefix) }
        ?.second
        ?: COMMERCIAL_DNS_SUFFIX
    return "https://$service.$region.$dnsSuffix"
}

private const val COMMERCIAL_DNS_SUFFIX = "amazonaws.com"

private val AWS_PARTITION_DNS_SUFFIXES = listOf(
    "cn-" to "amazonaws.com.cn",
    "us-iso-" to "c2s.ic.gov",
    "us-isob-" to "sc2s.sgov.gov",
    "eu-isoe-" to "cloud.adc-e.uk",
    "us-isof-" to "csp.hci.ic.gov",
    "eusc-" to "amazonaws.eu",
)
