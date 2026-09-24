package com.sabreware.aide.core.common.storage

/**
 * Reads a file that ships **inside the application** (as opposed to user data on disk, which is
 * [PlatformPaths]' business). Bundled assets are packaged by whichever module owns them — today the
 * Compose-resources bundle in `:core:designsystem` — so a lower layer that needs one takes this port
 * instead of reaching up into the UI module for the generated `Res` accessor.
 *
 * Paths are the bundle-relative ones the owning module publishes, e.g. `files/models_dev.json`.
 */
fun interface BundledAssetReader {
    suspend fun readBytes(path: String): ByteArray
}
