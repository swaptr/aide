package com.sabreware.aide.core.domain.model

import kotlinx.serialization.Serializable

/**
 * A user-imported local model (BYO `.litertlm`). Persisted in the [ModelDocuments.ImportedModels] document; the model file is
 * copied into the normal [ModelStorage] layout, so the resulting
 * [LocalLlmModel] flows through download-status/gate/engine-load with no special-casing. `downloadUrl`
 * is null ⇒ `requiresDownload=false` (already on disk, nothing to fetch).
 */
@Serializable
data class ImportedModelEntry(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sizeBytes: Long? = null,
    val topK: Int? = null,
    val topP: Float? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val visionIn: Boolean = false,
    val audioIn: Boolean = false,
    val thinking: Boolean = false,
    val preferGpu: Boolean = true,
)

/** The user-supplied half of an import (everything except the derived file metadata). */
data class ModelImportConfig(
    val displayName: String,
    val topK: Int? = null,
    val topP: Float? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val visionIn: Boolean = false,
    val audioIn: Boolean = false,
    val thinking: Boolean = false,
    val preferGpu: Boolean = true,
)

/** Maps an imported record to a runnable [LocalLlmModel] spec — the imported peer of `AllowedModel.toModelSpec`. */
fun ImportedModelEntry.toSpec(): LocalLlmModel = LocalLlmModel(
    id = id,
    displayName = displayName,
    family = displayName,
    params = "",
    quantization = "",
    artifact = ModelArtifact(
        downloadUrl = null, // already on disk; nothing to download
        fileName = fileName,
        sizeBytes = sizeBytes,
        commitHash = null,
    ),
    minRamGb = 0,
    recommendedRamGb = 0,
    capabilities = ChatCapabilities(
        visionIn = visionIn,
        audioIn = audioIn,
        toolsLocal = true,
        structuredOutput = ChatCapabilities.StructuredOutput.None,
        thinking = if (thinking) ChatCapabilities.ThinkingMode.Toggle else ChatCapabilities.ThinkingMode.None,
        embeddings = false,
        maxContext = maxTokens ?: 4096,
        maxOutput = maxTokens ?: 2048,
    ),
    defaultBackend = if (preferGpu) ModelBackend.GPU else ModelBackend.CPU,
    licenseName = "Imported model",
    licenseUrl = "",
    sourceUrl = "",
    defaultConfig = ModelDefaultConfig(
        topK = topK,
        topP = topP,
        temperature = temperature,
        maxTokens = maxTokens,
    ),
    taskTypes = listOf("llm_chat"),
    description = "Imported by you",
)

/** Stable id + on-disk folder name for an imported model; namespaced so it never collides with the allowlist. */
fun importedModelId(displayName: String): String =
    "import-" + displayName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "model" }
