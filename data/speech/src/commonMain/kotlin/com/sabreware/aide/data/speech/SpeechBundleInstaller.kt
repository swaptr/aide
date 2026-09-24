package com.sabreware.aide.data.speech

import com.sabreware.aide.core.domain.speech.SpeechAssetSpec

/**
 * Unpacks a downloaded speech bundle into the form the engines load.
 *
 * A port rather than a concrete call because the unpacking itself is `java.io` (commons-compress over a
 * `.tar.bz2`), while its caller — [com.sabreware.aide.data.download.SpeechAssetSource] — is common. Throws
 * on failure: the download stack treats a bundle that would not unpack as a failed download rather than
 * leaving a half-installed directory behind.
 */
fun interface SpeechBundleInstaller {
    /** Idempotent (marker-guarded): an already-extracted bundle is left alone. */
    suspend fun install(spec: SpeechAssetSpec)
}
