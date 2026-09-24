package com.sabreware.aide.data.speech

import com.sabreware.aide.core.domain.usecase.ResidencyDurations
import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.model.ModelSelectionStore
import com.sabreware.aide.core.domain.model.ProviderCatalog
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.model.Residency
import com.sabreware.aide.core.domain.model.ResidencyHandle
import com.sabreware.aide.core.domain.model.ResidencyManager
import com.sabreware.aide.core.domain.model.ResidentModel
import com.sabreware.aide.core.domain.model.activeModelFor
import com.sabreware.aide.core.domain.speech.CloudSpeechCatalog
import com.sabreware.aide.core.domain.speech.awaitById
import com.sabreware.aide.core.domain.speech.SpeechAvailability
import com.sabreware.aide.core.domain.speech.SpeechEngineRepository
import com.sabreware.aide.core.domain.speech.SpeechEngineRepository.Role
import com.sabreware.aide.core.domain.speech.SpeechProvider
import com.sabreware.aide.core.domain.speech.SpeechProviderRegistry
import com.sabreware.aide.core.domain.speech.SpeechResolutionPolicy
import com.sabreware.aide.core.domain.speech.SpeechStreamOutcome
import com.sabreware.aide.core.domain.speech.SttOptions
import com.sabreware.aide.core.domain.speech.SttStreamEvent
import com.sabreware.aide.core.domain.speech.TtsOptions
import com.sabreware.aide.core.domain.speech.TtsStreamEvent
import com.sabreware.aide.core.domain.speech.VadEvent
import com.sabreware.aide.core.domain.speech.speechProviderPreference
import com.sabreware.aide.core.domain.util.AideLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * The one [SpeechEngineRepository]. Which engine serves a role is decided by the injected
 * [SpeechResolutionPolicy] (pinned preference → the vendor of the active cloud model → the platform's
 * ladder → its terminal fallback), so a target with a different engine line-up supplies a different ladder
 * rather than a second repository class — the android/desktop pair this replaced differed in `resolve()`
 * and nothing else.
 *
 * [cloudModels] is how "Auto" honours a cloud pick without a second preference: choosing an OpenAI
 * transcription model in Models is the intent, and the repository reads it back through the catalog
 * rather than parsing the id. A cloud vendor never enters through the ladder itself — that would make a
 * paid endpoint the silent fallback for an on-device engine that failed to load.
 */
class SpeechEngineRepositoryImpl(
    private val providers: SpeechProviderRegistry,
    private val policy: SpeechResolutionPolicy,
    private val prefs: PreferenceStore,
    private val selection: ModelSelectionStore,
    private val residency: ResidencyManager,
    private val cloudModels: CloudSpeechCatalog,
) : SpeechEngineRepository {

    private val _currentProvider = MutableStateFlow(policy.terminal)
    override val currentProviderFlow: StateFlow<ProviderId> = _currentProvider.asStateFlow()

    override suspend fun resolve(role: Role): SpeechProvider = ladder(role).first().let { (id, p) ->
        p.select(id)
    }

    /**
     * Every engine that could serve [role], best first: the pinned provider (when it is capable), else —
     * in Auto — the vendor that owns the active cloud model for this role (when it is capable), then the
     * policy ladder, then the terminal.
     *
     * The terminal rung is included WITHOUT a capability check so the streaming APIs can report the failure
     * as an Error event on the flow rather than throwing out of resolve(). The list — rather than a single
     * winner — is what lets a rung that *declares* itself capable and then fails to LOAD demote instead of
     * ending the turn: declared availability cannot see a corrupt bundle, and a JNI throw at load used to be
     * terminal even with a working engine sitting right below it.
     */
    private suspend fun ladder(role: Role): List<Pair<ProviderId, SpeechProvider>> = buildList {
        val pinned = prefs.speechProviderPreference().first()
        if (pinned != null) {
            capableOrNull(pinned, role)?.let { add(pinned to it) }
        } else {
            activeOwner(role)?.let { owner -> capableOrNull(owner, role)?.let { add(owner to it) } }
        }
        policy.preferred.forEach { candidate ->
            if (none { it.first == candidate }) capableOrNull(candidate, role)?.let { add(candidate to it) }
        }
        if (none { it.first == policy.terminal }) {
            add(policy.terminal to providers.require(policy.terminal))
        }
    }

    /**
     * The provider whose model sits in this role's active slot — a cloud vendor, or the provider of a
     * built-in model (the host's recognizer or voice). Null when the slot holds a downloaded asset or nothing.
     */
    private suspend fun activeOwner(role: Role): ProviderId? {
        val id = selection.activeModelFor(role.modality).first() ?: return null
        // Waits for the connections to be read: a cloud pick is not "no owner" just because they have not.
        return providers.ownerOfBuiltIn(id) ?: cloudModels.awaitById(id)?.provider
    }

    private suspend fun capableOrNull(id: ProviderId, role: Role): SpeechProvider? =
        providers.await(id)?.takeIf { capable(it.availability(), role) }

    private fun SpeechProvider.select(id: ProviderId): SpeechProvider = also { _currentProvider.value = id }

    override suspend fun acquire(role: Role): ResidencyHandle =
        residency.acquire(SpeechResidentModel(role, resolve(role), activeAssetSizeBytes(role)))

    // The resident model's RAM estimate (D1): without it a 483 MB Whisper reported 0 bytes and the LRU
    // trim tie-break ("evict larger first") treated it as the lightest resident. Resolve the active
    // asset's declared size the same way the Sherpa engines pick which weights to load.
    private suspend fun activeAssetSizeBytes(role: Role): Long? =
        selection.activeModelFor(role.modality).first()?.let(SpeechAssetCatalog::findById)?.sizeBytes

    // Adapter bridging a resolved speech engine to the ResidencyManager. Sherpa (local weights) is
    // LOADED → refcounted + keepAlive-evicted; a system engine holds no weights we manage → NONE no-op.
    private class SpeechResidentModel(
        private val role: Role,
        private val provider: SpeechProvider,
        private val sizeBytes: Long?,
    ) : ResidentModel {
        override val modality = role.modality
        override val key = "${provider.id.value}:${modality.value}"
        override val residency =
            if (ProviderCatalog.of(provider.id).local) Residency.LOADED else Residency.NONE

        // Real weight size so the manager evicts the heavy native STT/TTS model first on a memory tie.
        override fun memoryEstimateBytes(): Long = sizeBytes ?: 0L

        // STT can preload its active model; TTS/VAD load lazily on first use and are still
        // refcount-protected + keepAlive-closed by the manager.
        override suspend fun load() {
            if (role == Role.STT) provider.stt?.warmUp()
        }

        override suspend fun close() {
            when (role) {
                Role.STT -> provider.stt?.close()
                Role.TTS -> provider.tts?.close()
                Role.VAD -> provider.vad?.close()
            }
        }
    }

    override fun recognize(audio: Flow<FloatArray>, options: SttOptions): Flow<SttStreamEvent> = flow {
        val rungs = ladder(Role.STT)
        var lastError: Throwable? = null
        rungs.forEachIndexed { index, (id, provider) ->
            val stt = provider.stt
            if (stt == null) {
                lastError = IllegalStateException("Provider $id cannot transcribe")
                return@forEachIndexed
            }
            provider.select(id)
            var emitted = false
            try {
                stt.recognize(audio, options).collect { emitted = true; emit(it) }
                return@flow
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                lastError = t
                // Demote only while nothing has reached the caller. Once a partial transcript is out,
                // restarting on another engine would duplicate speech the user already saw — and the audio
                // that produced it is gone.
                if (emitted || index == rungs.lastIndex) {
                    AideLog.w(TAG, "recognize() failed on $id", t)
                    emit(SttStreamEvent.End(SpeechStreamOutcome.Error(t.message ?: "recognize() failed", t)))
                    return@flow
                }
                AideLog.w(TAG, "recognize() failed to start on $id — demoting to the next engine", t)
            }
        }
        emit(
            SttStreamEvent.End(
                SpeechStreamOutcome.Error(lastError?.message ?: "No speech engine could transcribe", lastError),
            ),
        )
    }

    // Callers must NOT open AudioCapturer when true — two VOICE_RECOGNITION clients race
    // and the system reports NO_SPEECH_DETECTED.
    override suspend fun resolvedSttOwnsAudioInput(): Boolean =
        resolve(Role.STT).stt?.ownsAudioInput == true

    /** Resolves the STT provider and loads its active model. Fire-and-forget from
     *  surface-show callbacks so the first mic tap has the model already in memory. */
    override suspend fun warmUpStt() {
        // Through the residency manager, so the preloaded weights (a ~480 MB Whisper) have a slot: they idle
        // out, are freed when every surface hides, and count toward the next admission. A direct warmUp()
        // loaded them where nothing would ever free them.
        runCatching { acquire(Role.STT).release(ResidencyDurations.VOICE_KEEPALIVE_MS) }
            .onFailure { if (it is CancellationException) throw it }
    }

    override fun synthesize(text: String, options: TtsOptions): Flow<TtsStreamEvent> = flow {
        val rungs = ladder(Role.TTS)
        var lastError: Throwable? = null
        rungs.forEachIndexed { index, (id, provider) ->
            val tts = provider.tts
            if (tts == null) {
                lastError = IllegalStateException("Provider $id cannot synthesise")
                return@forEachIndexed
            }
            provider.select(id)
            var emitted = false
            try {
                tts.synthesize(text, options).collect { emitted = true; emit(it) }
                return@flow
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                lastError = t
                if (!emitted && index != rungs.lastIndex) {
                    AideLog.w(TAG, "synthesize() failed to start on $id — demoting to the next engine", t)
                    return@forEachIndexed
                }
                AideLog.w(TAG, "synthesize() failed on $id", t)
                emit(
                    TtsStreamEvent.End(
                        SpeechStreamOutcome.Error(t.message ?: "synthesize() failed", t),
                    ),
                )
                return@flow
            }
        }
        emit(
            TtsStreamEvent.End(
                SpeechStreamOutcome.Error(lastError?.message ?: "No speech engine could synthesise", lastError),
            ),
        )
    }

    override fun vad(audio: Flow<FloatArray>): Flow<VadEvent> = flow {
        val vad = resolve(Role.VAD).vad ?: return@flow
        vad.process(audio).collect { emit(it) }
    }

    override suspend fun availability(role: Role): SpeechAvailability = resolve(role).availability()

    // True for Sherpa (streaming PCM); false for system TTS (out-of-band playback).
    // Callers route to AudioPlayer only when true.
    override suspend fun resolvedTtsSupportsStreamingPcm(): Boolean =
        resolve(Role.TTS).tts?.supportsStreamingPcm == true

    private fun capable(a: SpeechAvailability, role: Role): Boolean = when (role) {
        Role.STT -> a.canStt
        Role.TTS -> a.canTts
        Role.VAD -> a.canVad
    }

    companion object {
        private const val TAG = "SpeechEngine"
    }
}

private val Role.modality: Modality
    get() = when (this) {
        Role.STT -> Modality.Asr
        Role.TTS -> Modality.Tts
        Role.VAD -> Modality.Vad
    }
