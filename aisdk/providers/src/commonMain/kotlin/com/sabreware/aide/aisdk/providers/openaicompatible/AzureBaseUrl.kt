package com.sabreware.aide.aisdk.providers.openaicompatible

import com.sabreware.aide.aisdk.util.DownloadUrl

/**
 * What a caller-supplied Azure base URL says about the endpoint behind it.
 *
 * Mirrors the reference's `getAzureOpenAIBaseURLInfo` (`azure-openai-provider.ts`, `993e900` and
 * `85db433`). Three hosts are Azure OpenAI — the classic `*.openai.azure.com`, AI Foundry's
 * `*.services.ai.azure.com` and Cognitive Services' `*.cognitiveservices.azure.com` — and anything else
 * is a gateway that owns its own routing and versioning. Kept beside the vendor table until the
 * orchestrator folds it into `providers/azure/AzureEndpoints.kt`, which declares the same contract.
 */
internal data class AzureBaseUrlInfo(
    val isAzureOpenAI: Boolean,
    /** A Foundry PROJECT endpoint (`/api/projects/{name}`): versions itself, and wants explicit item types. */
    val isFoundryProject: Boolean,
    /** A base that already ends in `/openai/v1`, which is used as-is. */
    val isVersioned: Boolean,
) {
    /** Foundry project Responses endpoints reject an input message that does not say `type: "message"`. */
    val explicitMessageItemType: Boolean get() = isFoundryProject
}

internal fun azureBaseUrlInfo(baseUrl: String?): AzureBaseUrlInfo {
    if (baseUrl == null) return AzureBaseUrlInfo(isAzureOpenAI = true, isFoundryProject = false, isVersioned = false)
    val host = DownloadUrl.hostOf(baseUrl).orEmpty()
    val path = "/" + baseUrl.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')
    val pathname = path.trimEnd('/')
    val isAzureOpenAI = host.endsWith(".openai.azure.com") ||
        host.endsWith(".services.ai.azure.com") ||
        host.endsWith(".cognitiveservices.azure.com")
    return AzureBaseUrlInfo(
        isAzureOpenAI = isAzureOpenAI,
        isFoundryProject = host.endsWith(".services.ai.azure.com") && pathname.startsWith("/api/projects/"),
        isVersioned = isAzureOpenAI && pathname.lowercase().endsWith("/openai/v1"),
    )
}

/**
 * The complete request URL for [path] under [baseUrl].
 *
 * A gateway, and a complete `/openai/v1` base, own their versioning: `{base}/{path}`. An unversioned
 * Azure base gets the v1 API form, `{base}/v1/{path}` with no deployment segment, and `api-version` is
 * appended only where the host still reads it — not on a versioned base and not on a Foundry project.
 * A blank [apiVersion] appends nothing, which is how the Responses surface opts out.
 */
internal fun azureRequestUrl(baseUrl: String, info: AzureBaseUrlInfo, path: String, apiVersion: String?): String {
    val prefix = baseUrl.trimEnd('/')
    val relative = path.trimStart('/')
    val url = if (!info.isAzureOpenAI || info.isVersioned) "$prefix/$relative" else "$prefix/v1/$relative"
    val versioned = apiVersion?.takeIf { it.isNotBlank() && info.isAzureOpenAI && !info.isVersioned && !info.isFoundryProject }
    return if (versioned == null) url else "$url?api-version=$versioned"
}
