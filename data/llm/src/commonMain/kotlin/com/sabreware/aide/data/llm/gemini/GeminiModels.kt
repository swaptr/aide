package com.sabreware.aide.data.llm.gemini

import com.sabreware.aide.aisdk.providers.google.GOOGLE_DEFAULT_BASE_URL
import com.sabreware.aide.core.domain.model.ChatCapabilities
import com.sabreware.aide.core.domain.model.ChatModelSpec
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.data.catalog.RemoteCatalog

/**
 * Curated Gemini catalog. Google's ListModels endpoint is capability-blind and noisy (embeddings, TTS,
 * image, legacy aliases), so — like the OpenAI-compatible catalog's id markers — we mint a small,
 * known-good chat set rather than scrape the wire. `id` is the bare Gemini wire model id (also the
 * [com.sabreware.aide.core.domain.model.ModelSpec.remoteName]). Living config: add a row when Google ships a model.
 */
object GeminiModels {

    data class Entry(val id: String, val displayName: String)

    // Verified against ai.google.dev/gemini-api/docs/models (2026-08-04): 3.6/3.5/3.1 are the current
    // stable chat generation; 2.5 family remains available; 2.0-flash is SHUT DOWN (removed here).
    val curated: List<Entry> = listOf(
        Entry("gemini-3.6-flash", "Gemini 3.6 Flash"),
        Entry("gemini-3.5-flash", "Gemini 3.5 Flash"),
        Entry("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite"),
        Entry("gemini-3.1-pro-preview", "Gemini 3.1 Pro (Preview)"),
        Entry("gemini-3.1-flash-lite", "Gemini 3.1 Flash-Lite"),
        Entry("gemini-2.5-pro", "Gemini 2.5 Pro"),
        Entry("gemini-2.5-flash", "Gemini 2.5 Flash"),
        Entry("gemini-2.5-flash-lite", "Gemini 2.5 Flash-Lite"),
    )

    // [caps] resolves capabilities from the platform's models.dev registry (passed in — commonMain can't
    // reach the Android-asset-backed registry directly).
    fun catalogSpecs(provider: ProviderId, caps: (String) -> ChatCapabilities?): List<ChatModelSpec> =
        curated.map { RemoteCatalog.geminiSpec(provider, it.id, caps, it.displayName) }

    /**
     * The base URL the `:aisdk` Google provider needs, from whatever the user stored.
     *
     * The provider appends `models/{id}:streamGenerateContent` to its base, so the base has to carry the
     * API version. The connect form used to preset the bare host (`https://generativelanguage.googleapis.com`,
     * no version — the previous client added it), and a config saved then would now 404 every chat, image
     * and speech call. A stored URL without a version segment gets the provider's own default version;
     * one that names a version is honoured as typed.
     */
    fun baseUrl(stored: String?): String {
        val trimmed = stored?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return GOOGLE_DEFAULT_BASE_URL
        val path = trimmed.substringAfter("://", "").substringAfter('/', "")
        val versioned = path.split('/').any { it.matches(VERSION_SEGMENT) }
        return if (versioned) trimmed else "$trimmed/$DEFAULT_VERSION"
    }

    private val VERSION_SEGMENT = Regex("v\\d+(beta\\d*|alpha\\d*)?")
    private val DEFAULT_VERSION = GOOGLE_DEFAULT_BASE_URL.substringAfterLast('/')
}
