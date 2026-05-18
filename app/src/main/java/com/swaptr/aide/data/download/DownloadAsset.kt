package com.swaptr.aide.data.download

import java.io.File

// Engine writes partFile then renames to finalFile — crash never leaves half-written model.
data class DownloadAsset(
    val handle: AssetHandle,
    val displayName: String,
    val downloadUrl: String,
    val partFile: File,
    val finalFile: File,
    val sizeBytes: Long?,
)
