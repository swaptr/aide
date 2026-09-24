package com.sabreware.aide.data.catalog
import com.sabreware.aide.core.domain.model.ChatCapabilities
import com.sabreware.aide.core.domain.model.LocalLlmModel
import com.sabreware.aide.core.domain.model.ModelArtifact
import com.sabreware.aide.core.domain.model.ModelBackend
import com.sabreware.aide.core.domain.model.ModelDefaultConfig
import com.sabreware.aide.core.domain.model.ChatModelSpec

// Maps gallery's AllowedModel → aide's ModelSpec. Always ProviderId.LOCAL; AICore
// variants become orphan specs (kept visible, fail at load — by design).
fun AllowedModel.toModelSpec(): ChatModelSpec {
    val downloadUrl = url ?: when {
        modelId != null && commitHash != null && modelFile != null ->
            "https://huggingface.co/$modelId/resolve/$commitHash/$modelFile?download=true"
        else -> null
    }

    val capabilitySet = ChatCapabilities(
        visionIn = llmSupportImage == true,
        audioIn = llmSupportAudio == true,
        // Default tools=true for LLM tasks; engines that don't emit tool calls no-op.
        toolsLocal = taskTypes.any { it.startsWith("llm_") },
        structuredOutput = ChatCapabilities.StructuredOutput.None,
        thinking = if (capabilities.any { it.equals("llm_thinking", ignoreCase = true) }) {
            ChatCapabilities.ThinkingMode.Toggle
        } else {
            ChatCapabilities.ThinkingMode.None
        },
        embeddings = false,
        maxContext = defaultConfig.maxContextLength ?: defaultConfig.maxTokens ?: 4096,
        maxOutput = defaultConfig.maxTokens ?: 2048,
    )

    val backend = parseDefaultBackend(defaultConfig.accelerators)

    return LocalLlmModel(
        id = sanitizeId(name),
        displayName = name,
        family = name.substringBeforeLast('-', name).takeIf { it.isNotBlank() } ?: name,
        params = variantLabel ?: "",
        quantization = "",
        artifact = ModelArtifact(
            downloadUrl = downloadUrl,
            fileName = modelFile,
            sizeBytes = sizeInBytes,
            hfRepoId = modelId,
            commitHash = commitHash,
            previousCommits = updatableModelFiles
                .filter { it.fileName == modelFile && it.commitHash.isNotBlank() }
                .map { it.commitHash },
        ),
        minRamGb = minDeviceMemoryInGb ?: 0,
        recommendedRamGb = minDeviceMemoryInGb ?: 0,
        capabilities = capabilitySet,
        defaultBackend = backend,
        licenseName = "Model Terms (see source)",
        licenseUrl = modelId?.let { "https://huggingface.co/$it" } ?: "https://huggingface.co",
        sourceUrl = modelId?.let { "https://huggingface.co/$it" } ?: "https://huggingface.co",
        defaultConfig = ModelDefaultConfig(
            topK = defaultConfig.topK,
            topP = defaultConfig.topP,
            temperature = defaultConfig.temperature,
            maxTokens = defaultConfig.maxTokens,
            maxContextLength = defaultConfig.maxContextLength,
            accelerators = defaultConfig.accelerators,
            visionAccelerator = defaultConfig.visionAccelerator,
        ),
        taskTypes = taskTypes,
        runtimeType = runtimeType,
        minDeviceMemoryInGb = minDeviceMemoryInGb,
        description = description,
        learnMoreUrl = modelId?.let { "https://huggingface.co/$it" },
        updateInfo = updateInfo,
        parentModelName = parentModelName,
    )
}

// Doubles as ModelSpec.id and the folder name under filesDir/models — must be stable.
private fun sanitizeId(name: String): String =
    name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

// Preferred backend only; LlmEngineRepository.loadInternal still does GPU→CPU fallback.
// GPU-first: if "gpu" appears anywhere in the JSON accelerators list we prefer it,
// regardless of element order. Gallery picks first-in-list and exposes a picker UI;
// aide has no picker, so honour the user's explicit "GPU everywhere" instruction here.
// Only a list that lacks "gpu" entirely (e.g. CPU-only models) falls back to CPU.
private fun parseDefaultBackend(accelerators: String?): ModelBackend {
    if (accelerators.isNullOrBlank()) return ModelBackend.GPU
    val items = accelerators.split(",").map { it.trim().lowercase() }
    return if ("gpu" in items) ModelBackend.GPU else ModelBackend.CPU
}
