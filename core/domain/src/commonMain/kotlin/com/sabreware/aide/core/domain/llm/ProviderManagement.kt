package com.sabreware.aide.core.domain.llm

import com.sabreware.aide.core.domain.model.ModelMetadata
import com.sabreware.aide.core.domain.model.ChatModelSpec
import com.sabreware.aide.core.domain.provider.ConnectionTestResult
import kotlinx.coroutines.flow.Flow

interface ProviderManagement {
    val remoteCatalogFlow: Flow<RemoteCatalogState>

    suspend fun refreshCatalog()

    suspend fun testConnection(): ConnectionTestResult?

    fun pullModel(name: String): Flow<PullProgress>? = null

    suspend fun hydrateSpec(modelId: String) {}

    /** Rich per-model metadata (caps + display detail) the provider serves on selection; null when unknown. */
    suspend fun modelMetadata(modelId: String): ModelMetadata? = null
}

sealed interface RemoteCatalogState {
    /**
     * Nothing known yet — the provider has not finished reading its stored config and last-good listing.
     *
     * Distinct from [Unconfigured], which is a definite "no credentials". Starting every provider at
     * `Unconfigured` is what made a fully set-up cloud provider report as unconfigured for the whole of a
     * cold start, so the model gate said `NoModel` and the chat header flashed "No model" before the
     * catalog landed. A registry that sees this holds its snapshot back instead of answering "no such
     * model" from an incomplete picture.
     */
    data object Unknown : RemoteCatalogState
    data object Unconfigured : RemoteCatalogState
    data class Ready(val specs: List<ChatModelSpec>, val fetchedAt: Long) : RemoteCatalogState
    data class Refreshing(val previous: List<ChatModelSpec>) : RemoteCatalogState
    data class Failed(val previous: List<ChatModelSpec>, val message: String) : RemoteCatalogState
}

/** Specs currently known for this state — live ([Ready]) or last-good ([Refreshing]/[Failed]); empty when
 *  [Unconfigured] or [Unknown]. "Empty" and "not yet known" read the same here on purpose: a caller that
 *  needs to tell them apart matches on [isSettled]. */
val RemoteCatalogState.currentSpecs: List<ChatModelSpec>
    get() = when (this) {
        is RemoteCatalogState.Ready -> specs
        is RemoteCatalogState.Refreshing -> previous
        is RemoteCatalogState.Failed -> previous
        RemoteCatalogState.Unconfigured, RemoteCatalogState.Unknown -> emptyList()
    }

/**
 * Whether [currentSpecs] is this provider's ANSWER rather than the absence of one.
 *
 * The distinction is load-bearing, and getting it wrong is what flashed "Set up a model to begin" over a
 * configured provider on every cold start: the registry merges providers into one snapshot, so a single
 * provider that has not answered yet must hold the whole snapshot back — otherwise the chat header commits
 * to "No model", the composer disables send, and the model gate reads `NoModel`, all from a picture that
 * simply is not finished.
 *
 * - [RemoteCatalogState.Unknown] — has not read its stored config yet. Not an answer.
 * - [RemoteCatalogState.Refreshing] with **no** `previous` — a FIRST fetch is in flight and there is no
 *   last-good listing behind it, so it knows exactly as much as `Unknown` does. Not an answer. (With a
 *   `previous`, it is: the last-good listing stays on screen while the refresh runs behind it.)
 * - [RemoteCatalogState.Unconfigured] / [RemoteCatalogState.Ready] / [RemoteCatalogState.Failed] — settled,
 *   empty or not. `Failed` counts on purpose: a dead network must END the skeleton, not extend it forever.
 */
val RemoteCatalogState.isSettled: Boolean
    get() = when (this) {
        RemoteCatalogState.Unknown -> false
        is RemoteCatalogState.Refreshing -> previous.isNotEmpty()
        RemoteCatalogState.Unconfigured -> true
        is RemoteCatalogState.Ready -> true
        is RemoteCatalogState.Failed -> true
    }

data class PullProgress(
    val status: String,
    val percent: Float?,
    val terminal: Boolean,
    val error: String? = null,
)
