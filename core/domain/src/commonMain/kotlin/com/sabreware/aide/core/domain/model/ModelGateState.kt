package com.sabreware.aide.core.domain.model

/**
 * Single source of truth for "can the app hold a conversation right now?" — gates the IME, the assistant
 * and the chat VMs. Chat-typed on purpose: every consumer goes straight on to send a turn, and a modality
 * that cannot do that needs its own gate rather than a seat in this one.
 */
sealed interface ModelGateState {
    /**
     * The registry has not finished answering — no provider has reported its stored config and last-good
     * catalog yet. **Not** [NoModel], and the difference is the whole point: this used to be the seed of
     * [gateStateFlow][ModelRegistryRepository.gateStateFlow], so every surface opened onto a definite "you
     * have no model" for the first second or so of a cold start, over a model that was configured fine.
     *
     * A consumer treats it as "wait": stay locked, keep whatever it is showing, and offer no call to
     * action. It is transient by construction: the selection document is read in milliseconds, and the one
     * source the chosen model depends on settles, including by failing.
     */
    data object Unresolved : ModelGateState

    /**
     * [spec] can hold the conversation. [reroutedFrom] is set when it is NOT the model the user chose — the
     * chosen one is unavailable and the user's [ModelFallback] setting allowed a substitute. Every surface
     * must say so while it is true: a private app never sends to a different model without the user seeing it.
     */
    data class Ready(val spec: ChatModelSpec, val reroutedFrom: ModelCard? = null) : ModelGateState
    data class Downloading(val spec: ChatModelSpec, val progress: Float) : ModelGateState

    /**
     * The user chose [card], and it is not usable now: its weights are gone, or its provider was deconfigured
     * or no longer lists it. Settled — and deliberately NOT [NoModel] and never a substitute: the surface
     * says which model is missing and lets the user choose. Silently answering with a different model (worse,
     * a cloud one in place of an on-device one) is exactly what a private app must not do.
     */
    data class Missing(val card: ModelCard) : ModelGateState

    /** Settled: the user has not chosen a model. May prompt the user to choose or set one up. */
    data object NoModel : ModelGateState
}
