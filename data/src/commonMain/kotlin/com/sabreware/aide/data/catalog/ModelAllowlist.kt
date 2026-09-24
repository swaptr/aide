package com.sabreware.aide.data.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire-format mirror of gallery's allowlist JSON. Field names must match gallery exactly;
// all fields outside `name` nullable so cross-version JSONs still parse.
@Serializable
data class AllowlistDefaultConfig(
    val topK: Int? = null,
    val topP: Float? = null,
    val temperature: Float? = null,
    val accelerators: String? = null,
    val visionAccelerator: String? = null,
    val maxContextLength: Int? = null,
    val maxTokens: Int? = null,
)

/** Per-SOC file override for NPU-targeted bundles. Unused on Pixel 6a (GPU/CPU only). */
@Serializable
data class AllowlistSocModelFile(
    val modelFile: String? = null,
    val url: String? = null,
    val commitHash: String? = null,
    val sizeInBytes: Long? = null,
)

@Serializable
data class AllowlistUpdatableFile(
    val fileName: String,
    val commitHash: String,
)

@Serializable
data class AllowedModel(
    val name: String,
    val modelId: String? = null,
    val modelFile: String? = null,
    val commitHash: String? = null,
    val description: String? = null,
    val sizeInBytes: Long? = null,
    val defaultConfig: AllowlistDefaultConfig = AllowlistDefaultConfig(),
    val taskTypes: List<String> = emptyList(),
    val disabled: Boolean? = null,
    val llmSupportImage: Boolean? = null,
    val llmSupportAudio: Boolean? = null,
    val capabilities: List<String> = emptyList(),
    val minDeviceMemoryInGb: Int? = null,
    val bestForTaskTypes: List<String> = emptyList(),
    val localModelFilePathOverride: String? = null,
    val url: String? = null,
    val socToModelFiles: Map<String, AllowlistSocModelFile>? = null,
    val runtimeType: String? = null,
    val parentModelName: String? = null,
    val variantLabel: String? = null,
    val capabilityToTaskTypes: Map<String, List<String>> = emptyMap(),
    val updatableModelFiles: List<AllowlistUpdatableFile> = emptyList(),
    val updateInfo: String? = null,
)

@Serializable
data class AllowlistNamedDeviceGroup(
    val groupName: String,
    val description: String? = null,
    val deviceModels: List<String> = emptyList(),
)

@Serializable
data class AllowlistDeviceRequirements(
    val allowedDeviceGroups: List<AllowlistNamedDeviceGroup> = emptyList(),
)

@Serializable
data class ModelAllowlist(
    val models: List<AllowedModel> = emptyList(),
    @SerialName("aicoreRequirements")
    val aicoreRequirements: AllowlistDeviceRequirements? = null,
)
