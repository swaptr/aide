package com.swaptr.aide.data.catalog

import com.swaptr.aide.data.speech.DownloadableSpec

enum class ModelBackend { CPU, GPU }

// Remote specs leave this null (wire protocol uses its own defaults).
// accelerators is raw JSON string; router uses ModelSpec.defaultBackend for the actual pick.
data class ModelDefaultConfig(
    val topK: Int? = null,
    val topP: Float? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val maxContextLength: Int? = null,
    val accelerators: String? = null,
    val visionAccelerator: String? = null,
)

// Unified shape for local (LiteRT) and remote (Ollama) models. LOCAL carries downloadUrl/
// fileName/sizeBytes; remote carries remoteName (wire id for POST /api/chat).
data class ModelSpec(
    override val id: String,
    override val displayName: String,
    val family: String,
    val params: String,
    val quantization: String,
    val provider: ProviderId = ProviderId.LOCAL,
    /** Wire model id passed to the backend. Null for LOCAL (engine resolves by [id]). */
    val remoteName: String? = null,
    /** LOCAL only. Server URL the downloader streams the bundle from. */
    override val downloadUrl: String? = null,
    /** LOCAL only. On-disk filename inside `filesDir/models`. */
    override val fileName: String? = null,
    /** LOCAL only. Display-only estimate of on-disk size. Server's Content-Length is authoritative. */
    override val sizeBytes: Long? = null,
    val minRamGb: Int,
    val recommendedRamGb: Int,
    val capabilities: CapabilitySet,
    val defaultBackend: ModelBackend,
    val licenseName: String,
    val licenseUrl: String,
    val sourceUrl: String,
    /** HuggingFace repo id (`{org}/{name}`). LOCAL only; used to build pinned download URLs. */
    val modelId: String? = null,
    /** HuggingFace commit hash the bundle was pinned to at allowlist publish time. */
    val commitHash: String? = null,
    /**
     * Older commit hashes for the same `fileName` (from allowlist `updatableModelFiles`).
     * Mirrors gallery's `isModelDownloaded` probe: if the active commit isn't on disk but a
     * previous one is, reuse the previous artifact instead of re-downloading. Empty for
     * remote specs and for models without legacy versions.
     */
    val previousCommits: List<String> = emptyList(),
    /** Per-model sampling defaults from the allowlist's `defaultConfig`. */
    val defaultConfig: ModelDefaultConfig? = null,
    /** Allowlist `taskTypes` (e.g. `["llm_chat", "llm_prompt_lab"]`). UI surface only. */
    val taskTypes: List<String> = emptyList(),
    /** Allowlist `runtimeType` (`"LITERT_LM"`, `"AICORE"`, or null). LiteRT engine only handles null/LITERT_LM. */
    val runtimeType: String? = null,
    /** Minimum device RAM gallery recommends for this bundle. UI hint. */
    val minDeviceMemoryInGb: Int? = null,
    /** Long-form description from the allowlist. May contain markdown links. */
    val description: String? = null,
    /** "Learn more" URL gallery shows. Defaults to the HF repo page. */
    val learnMoreUrl: String? = null,
    /** Free-form release-note text from the allowlist. */
    val updateInfo: String? = null,
) : DownloadableSpec {
    /** True iff the model needs an on-disk download before inference can run. */
    val requiresDownload: Boolean get() = downloadUrl != null
}
