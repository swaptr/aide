package com.sabreware.aide.core.domain.model

/**
 * The downloadable on-disk bundle for a LOCAL (LiteRT) model. Grouping these LOCAL-only fields means
 * a remote model simply has no artifact (`null`) instead of carrying stray nulls — a step toward the
 * sealed `LocalLlmModel`/`RemoteLlmModel` split (Phase 8).
 */
data class ModelArtifact(
    val downloadUrl: String?,
    val fileName: String?,
    val sizeBytes: Long?,
    /** HuggingFace repo id (`{org}/{name}`); used to build pinned download URLs. */
    val hfRepoId: String? = null,
    /** HuggingFace commit hash the bundle was pinned to at allowlist publish time. */
    val commitHash: String? = null,
    /**
     * Older commit hashes for the same [fileName] (allowlist `updatableModelFiles`). If the active
     * commit isn't on disk but a previous one is, reuse it instead of re-downloading.
     */
    val previousCommits: List<String> = emptyList(),
)
