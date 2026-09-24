package com.sabreware.aide.data.speech.cloud

import com.sabreware.aide.core.domain.connection.ConnectionRuntimes
import com.sabreware.aide.core.domain.connection.VendorId
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.speech.CloudSpeechCatalog
import com.sabreware.aide.core.domain.speech.CloudSpeechModelSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The cloud speech models each vendor serves — a hand-written list, like
 * [com.sabreware.aide.data.speech.SpeechAssetCatalog] and the image templates.
 *
 * Model ids are the ones each vendor's own SDK fixtures name, so a row here is one that has been seen on
 * the wire rather than read off a marketing page. Living config: add a row when a vendor ships a model.
 * A row becomes a model only through a connection ([forConnection]): its id is
 * `"<connectionId>:<remoteName>"`, so the per-modality active slot can hold it beside on-device asset ids
 * and name the account that serves it.
 */
object CloudSpeechTemplates {

    /** [vendor]'s speech models, named for connection [provider]; empty for a vendor that does not speak. */
    fun forConnection(vendor: VendorId, provider: ProviderId): List<CloudSpeechModelSpec> =
        rows.filter { it.vendor == vendor }.map { row ->
            CloudSpeechModelSpec(
                id = "${provider.value}:${row.remoteName}",
                displayName = row.displayName,
                modality = row.modality,
                provider = provider,
                remoteName = row.remoteName,
                blurb = row.blurb,
                isDefault = row.isDefault,
            )
        }

    /** The remote name [vendor] uses for [modality] when the user has picked none of its models. */
    fun defaultRemoteName(vendor: VendorId, modality: Modality): String? =
        rows.filter { it.vendor == vendor && it.modality == modality }
            .let { r -> r.firstOrNull { it.isDefault } ?: r.firstOrNull() }?.remoteName

    private class Row(
        val vendor: VendorId,
        val modality: Modality,
        val remoteName: String,
        val displayName: String,
        val blurb: String,
        val isDefault: Boolean,
    )

    private val rows: List<Row> = listOf(
        // OpenAI: the compat wire's `/audio/transcriptions` and `/audio/speech`.
        stt(VendorId.OPENAI_COMPATIBLE, "gpt-4o-mini-transcribe", "GPT-4o mini Transcribe", "Fast, accurate; the default.", isDefault = true),
        stt(VendorId.OPENAI_COMPATIBLE, "gpt-4o-transcribe", "GPT-4o Transcribe", "Highest accuracy."),
        stt(VendorId.OPENAI_COMPATIBLE, "whisper-1", "Whisper", "The original; returns timings."),
        tts(VendorId.OPENAI_COMPATIBLE, "gpt-4o-mini-tts", "GPT-4o mini TTS", "Natural, steerable; the default.", isDefault = true),
        tts(VendorId.OPENAI_COMPATIBLE, "tts-1", "TTS-1", "Lowest latency."),
        tts(VendorId.OPENAI_COMPATIBLE, "tts-1-hd", "TTS-1 HD", "Highest quality."),

        // ElevenLabs: voices are the product; Scribe is the transcription side.
        stt(VendorId.ELEVENLABS, "scribe_v1", "Scribe", "ElevenLabs transcription.", isDefault = true),
        tts(VendorId.ELEVENLABS, "eleven_multilingual_v2", "Multilingual v2", "Most natural; the default.", isDefault = true),
        tts(VendorId.ELEVENLABS, "eleven_flash_v2_5", "Flash v2.5", "Lowest latency."),
        tts(VendorId.ELEVENLABS, "eleven_v3", "Eleven v3", "Most expressive."),

        // Google: Gemini's audio-out and its dedicated transcription model.
        stt(VendorId.GEMINI, "gemini-3.5-transcribe", "Gemini 3.5 Transcribe", "Google transcription.", isDefault = true),
        tts(VendorId.GEMINI, "gemini-2.5-flash-preview-tts", "Gemini 2.5 Flash TTS", "Fast; the default.", isDefault = true),
        tts(VendorId.GEMINI, "gemini-2.5-pro-preview-tts", "Gemini 2.5 Pro TTS", "Highest quality."),
    )

    private fun stt(vendor: VendorId, remoteName: String, displayName: String, blurb: String, isDefault: Boolean = false) =
        Row(vendor, Modality.Asr, remoteName, displayName, blurb, isDefault)

    private fun tts(vendor: VendorId, remoteName: String, displayName: String, blurb: String, isDefault: Boolean = false) =
        Row(vendor, Modality.Tts, remoteName, displayName, blurb, isDefault)
}

/** [CloudSpeechCatalog] as the union of every connection's speech rows, in connection order. */
class ConnectedCloudSpeechCatalog(runtimes: ConnectionRuntimes, scope: CoroutineScope) : CloudSpeechCatalog {
    override val models: StateFlow<List<CloudSpeechModelSpec>?> = runtimes.runtimes
        .map { list -> list?.flatMap { it.speechModels } }
        .stateIn(scope, SharingStarted.Eagerly, runtimes.runtimes.value?.flatMap { it.speechModels })
}
