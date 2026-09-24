package com.sabreware.aide.core.domain.speech

import com.sabreware.aide.core.domain.llm.Provider
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.model.ModelDescriptor
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.provider.ProviderRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Serves the speech modalities (ASR / TTS / VAD). A capability of a [Provider]; `id` is inherited. */
interface SpeechProvider : Provider {
    val stt: SpeechRecognizerEngine?
    val tts: SpeechSynthesizerEngine?
    val vad: VadEngine?

    /**
     * Models this provider serves with nothing to download or configure — the host's own recognizer and
     * voice. Each is chosen like any other model (the role's active slot), and resolution routes a role
     * whose slot names one to this provider. A provider whose models are downloaded or listed elsewhere
     * (Sherpa, a cloud vendor) declares none.
     */
    val builtInModels: List<BuiltInSpeechModel> get() = emptyList()

    suspend fun availability(): SpeechAvailability
}

/** A speech model that ships with the host (see [SpeechProvider.builtInModels]). Never downloaded. */
data class BuiltInSpeechModel(
    override val id: String,
    override val displayName: String,
    override val modality: Modality,
    override val provider: ProviderId,
    /** One line for the picker row. */
    val blurb: String,
) : ModelDescriptor {
    override val downloadUrl: String? get() = null
    override val fileName: String? get() = null
    override val sizeBytes: Long? get() = null
}

/** Every speech-capable provider the running application contributed. */
class SpeechProviderRegistry(
    providers: Collection<SpeechProvider>,
    connected: StateFlow<List<SpeechProvider>?> = MutableStateFlow(emptyList<SpeechProvider>()),
) : ProviderRegistry<SpeechProvider>(providers, connected) {
    /** Every provider's [SpeechProvider.builtInModels], in binding order. */
    val builtInModels: List<BuiltInSpeechModel> get() = all.flatMap { it.builtInModels }

    /** The provider serving the built-in model [id], or null when [id] is not a built-in model. */
    fun ownerOfBuiltIn(id: String): ProviderId? = all.firstOrNull { p -> p.builtInModels.any { it.id == id } }?.id
}

/**
 * Which provider [SpeechEngineRepository.resolve] should pick, expressed as data so the resolution rule is
 * the only thing that varies per platform — there is no second repository class whose sole difference is a
 * `resolve()` body.
 *
 * The user's pinned preference always wins if that provider is registered and capable of the role. Failing
 * that the [ladder] is walked in order, and the LAST entry is the terminal fallback: it is returned without
 * a capability check, because the caller needs *a* provider to report the failure through (the streaming
 * APIs emit an `Error` event; they do not throw). A ladder entry that is not registered is skipped; a
 * missing terminal is a wiring bug and throws.
 *
 * - Android: `[SHERPA, ANDROID_SYSTEM]` — the system engines are always present, so they terminate the ladder.
 * - Desktop: `[SHERPA]` — Sherpa is the only engine, so it both leads and terminates.
 *
 * A platform gaining a second engine (a cloud TTS, say) extends its ladder; it does not gain a class.
 */
class SpeechResolutionPolicy(val ladder: List<ProviderId>) {
    init {
        require(ladder.isNotEmpty()) { "A speech resolution ladder needs at least a terminal provider" }
    }

    /** Tried in order, each only if it is registered AND capable of the requested role. */
    val preferred: List<ProviderId> get() = ladder.dropLast(1)

    /** Returned unconditionally when nothing above it can serve the role. */
    val terminal: ProviderId get() = ladder.last()
}
