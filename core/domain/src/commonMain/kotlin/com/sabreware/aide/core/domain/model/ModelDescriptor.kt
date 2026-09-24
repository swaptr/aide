package com.sabreware.aide.core.domain.model

import com.sabreware.aide.core.domain.download.DownloadableSpec

/**
 * The identity every model shares, whatever it generates: who serves it, which modality it belongs to, and
 * whether it has to be fetched before it can run.
 *
 * Deliberately **thin**. Both spec hierarchies — [ModelSpec] for LLMs,
 * [com.sabreware.aide.core.domain.speech.SpeechAssetSpec] for on-device speech — implement it and keep their
 * own bodies: merging them would reintroduce the stray nulls that splitting them removed (a speech asset has
 * no context window; a cloud chat model has no sample rate). What is lifted here is only what the
 * modality-agnostic machinery needs — acquisition, availability, tiering, `activeModelFor` — so that code
 * stops being written twice, once per hierarchy.
 *
 * It also gives speech a [requiresDownload] it previously could not express.
 */
interface ModelDescriptor : DownloadableSpec {
    override val id: String
    override val displayName: String

    /** Who serves this model. */
    val provider: ProviderId

    /** What it generates — chat / asr / tts / vad / image / embedding. Every modality is a peer. */
    val modality: Modality

    /**
     * True iff inference needs an on-disk download first. Keyed on a present download URL, NOT merely on
     * having an artifact: an orphan local spec carries an artifact whose `downloadUrl` is null (visible but
     * un-downloadable, fail-at-load by design) and must report false.
     */
    val requiresDownload: Boolean get() = downloadUrl != null
}
