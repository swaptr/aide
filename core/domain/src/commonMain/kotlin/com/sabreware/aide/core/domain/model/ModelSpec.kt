package com.sabreware.aide.core.domain.model

enum class ModelBackend { CPU, GPU }

// Remote specs leave this null (wire protocol uses its own defaults).
// accelerators is raw JSON string; router uses ModelSpec.defaultBackend for the actual pick.
data class ModelDefaultConfig(
    override val topK: Int? = null,
    override val topP: Float? = null,
    override val temperature: Float? = null,
    override val maxTokens: Int? = null,
    val maxContextLength: Int? = null,
    val accelerators: String? = null,
    val visionAccelerator: String? = null,
) : SamplerFields

/**
 * A model the app can run — everything true of one whatever it generates. Open, not sealed: a new modality
 * arrives as a new spec type in its own file, which is exactly what a closed hierarchy of
 * [LocalLlmModel]/[RemoteLlmModel] made impossible.
 *
 * Nothing chat-shaped lives here. Chat capabilities and chat sampling defaults are on [ChatModelSpec], so an
 * image or embedding spec is not forced to declare a context window and a thinking mode it has no meaning
 * for. What stays is the model's identity, where its weights are, and how it is described to the user.
 *
 * Locality is expressed by the fields, not by a subtype: a model with an [artifact] has weights on this
 * device, one with a [remoteName] is addressed over a wire, and the invariant that it is exactly one of the
 * two is upheld by the concrete types.
 */
interface ModelSpec : ModelDescriptor {
    override val id: String
    override val displayName: String
    val family: String
    val params: String
    val quantization: String
    override val provider: ProviderId

    /**
     * Transport trait: the model is served by a cloud endpoint (managed, hosted weights) rather
     * than a self-hosted/on-device runtime. Remote cloud specs set it; it is NOT a
     * provider-specific flag. Drives [ProviderTier] bucketing without id-prefix parsing.
     */
    val cloud: Boolean
    val minRamGb: Int
    val recommendedRamGb: Int
    val defaultBackend: ModelBackend
    val licenseName: String
    val licenseUrl: String
    val sourceUrl: String

    /** Allowlist `taskTypes` (e.g. `["llm_chat", "llm_prompt_lab"]`). UI surface only. */
    val taskTypes: List<String>

    /**
     * Allowlist `runtimeType` (`"LITERT_LM"`, `"AICORE"`, or null). Catalog-only metadata (C4): stored
     * but never branched on at runtime — there is no AICore engine, so the LiteRT engine handles every
     * local spec. Surfaced for the model-detail UI / future AICore routing only.
     */
    val runtimeType: String?

    /** Minimum device RAM gallery recommends for this bundle. UI hint. */
    val minDeviceMemoryInGb: Int?

    /** Long-form description from the allowlist. May contain markdown links. */
    val description: String?

    /** "Learn more" URL gallery shows. Defaults to the HF repo page. */
    val learnMoreUrl: String?

    /** Free-form release-note text from the allowlist. */
    val updateInfo: String?

    /** Family name shared by variants (e.g. "Gemma 3n"); groups sibling sizes in the model list. */
    val parentModelName: String?

    /** LOCAL only: the downloadable on-disk bundle. Null for a model served over a wire. */
    val artifact: ModelArtifact?

    /** Wire model id for the backend. Null for a model whose weights are on this device. */
    val remoteName: String?

    // DownloadableSpec members + flat convenience accessors delegate to [artifact] (null for remote).
    override val downloadUrl: String? get() = artifact?.downloadUrl
    override val fileName: String? get() = artifact?.fileName
    override val sizeBytes: Long? get() = artifact?.sizeBytes
    val modelId: String? get() = artifact?.hfRepoId
    val commitHash: String? get() = artifact?.commitHash
    val previousCommits: List<String> get() = artifact?.previousCommits ?: emptyList()
}

/**
 * A model served through the chat modality — the only kind that has [ChatCapabilities] and chat sampling
 * defaults. Holding these here rather than on [ModelSpec] is what lets a non-chat model exist: the chat
 * pipeline asks for a `ChatModelSpec`, so it cannot be handed something that has no context window, and an
 * image spec never has to invent one.
 */
interface ChatModelSpec : ModelSpec {
    override val modality: Modality get() = Modality.Chat
    val capabilities: ChatCapabilities

    /** Per-model sampling defaults from the allowlist's `defaultConfig`. */
    val defaultConfig: ModelDefaultConfig?
}

/** A local (LiteRT) chat model — always backed by a downloadable [artifact]; never has a [remoteName]. */
data class LocalLlmModel(
    override val id: String,
    override val displayName: String,
    override val family: String,
    override val params: String,
    override val quantization: String,
    override val artifact: ModelArtifact,
    override val minRamGb: Int,
    override val recommendedRamGb: Int,
    override val capabilities: ChatCapabilities,
    override val defaultBackend: ModelBackend,
    override val licenseName: String,
    override val licenseUrl: String,
    override val sourceUrl: String,
    override val defaultConfig: ModelDefaultConfig? = null,
    override val taskTypes: List<String> = emptyList(),
    override val runtimeType: String? = null,
    override val minDeviceMemoryInGb: Int? = null,
    override val description: String? = null,
    override val learnMoreUrl: String? = null,
    override val updateInfo: String? = null,
    override val parentModelName: String? = null,
) : ChatModelSpec {
    override val provider: ProviderId get() = ProviderId.LOCAL
    override val cloud: Boolean get() = false
    override val remoteName: String? get() = null
}

/** A remote (OpenAI-compatible) chat model — addressed by [remoteName]; never has an [artifact]. */
data class RemoteLlmModel(
    override val id: String,
    override val displayName: String,
    override val family: String,
    override val params: String,
    override val quantization: String,
    override val remoteName: String,
    override val cloud: Boolean,
    override val minRamGb: Int,
    override val recommendedRamGb: Int,
    override val capabilities: ChatCapabilities,
    override val defaultBackend: ModelBackend,
    override val licenseName: String,
    override val licenseUrl: String,
    override val sourceUrl: String,
    // The connection that serves this model — its id is the provider id.
    override val provider: ProviderId,
    override val defaultConfig: ModelDefaultConfig? = null,
    override val taskTypes: List<String> = emptyList(),
    override val runtimeType: String? = null,
    override val minDeviceMemoryInGb: Int? = null,
    override val description: String? = null,
    override val learnMoreUrl: String? = null,
    override val updateInfo: String? = null,
    override val parentModelName: String? = null,
) : ChatModelSpec {
    override val artifact: ModelArtifact? get() = null
}

/**
 * This model under [name] — how the user's alias becomes the name every surface paints (the chat header, the
 * keyboard, the model sheet), applied once where the registry builds its rows rather than at each surface.
 */
fun ChatModelSpec.named(name: String): ChatModelSpec = when {
    name == displayName -> this
    this is LocalLlmModel -> copy(displayName = name)
    this is RemoteLlmModel -> copy(displayName = name)
    else -> this
}
