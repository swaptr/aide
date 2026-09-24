package com.sabreware.aide.core.domain.model

import kotlinx.serialization.Serializable

/**
 * Every model choice the user has made, as ONE document — the single source of truth for "which model".
 *
 * It replaced four preference keys (`active_model_by_modality_json`, `last_used_by_tier_json`,
 * `last_used_model_id`, `ui.active_model_card_json`), two of them with hand-written codecs, that were written
 * one at a time and could be read half-updated. Now a pick is one atomic write, and the whole answer is one
 * small file read at process start ([ModelDocuments.Selection]) — so every surface, the keyboard included,
 * knows what the user chose before it draws anything.
 *
 * Every field has a default: that is what lets a field be added without touching a file already on disk
 * (see [com.sabreware.aide.core.common.persist.PersistedDocument]).
 */
@Serializable
data class ModelSelection(
    /** The chosen model per [Modality] (`modality.value -> modelId`). Chat's entry is what the gate serves. */
    val activeByModality: Map<String, String> = emptyMap(),
    /** Per [ProviderTier.key]: flipping tiers restores that tier's pick instead of the default. */
    val lastUsedByTier: Map<String, String> = emptyMap(),
    /** The model the chat header opens on. Null = the user has not picked one. */
    val lastUsedModelId: String? = null,
    /** What to DRAW for each id referenced above, so no surface needs the registry to paint a name. */
    val cards: Map<String, ModelCard> = emptyMap(),
) {
    fun activeFor(modality: Modality): String? = activeByModality[modality.value]

    /**
     * THE model the user chose for [modality] — what every surface (chat, keyboard, assistant, voice) acts
     * on. For chat that is the model the chat header shows ([lastUsedModelId], set by every pick), falling
     * back to the chat slot ([activeFor]); two surfaces answering "which model?" differently is how the
     * keyboard came to say "no model" to a user whose chat was talking to a cloud model just fine.
     */
    fun chosenFor(modality: Modality): String? =
        if (modality == Modality.Chat) lastUsedModelId ?: activeFor(modality) else activeFor(modality)

    fun lastUsedFor(tier: ProviderTier): String? = lastUsedByTier[tier.key]

    /** The card for the chat header's model, or null when none is picked or it was never seen. */
    val lastUsedCard: ModelCard? get() = lastUsedModelId?.let(cards::get)

    fun withActive(modality: Modality, id: String?): ModelSelection = copy(
        activeByModality = if (id == null) activeByModality - modality.value
        else activeByModality + (modality.value to id),
    )

    /** Records [card] as used: its tier's slot, the header's model, and what to draw for it. */
    fun withUsed(card: ModelCard, tier: ProviderTier): ModelSelection = copy(
        lastUsedByTier = lastUsedByTier + (tier.key to card.id),
        lastUsedModelId = card.id,
        cards = cards + (card.id to card),
    )

    /** Refreshes the drawn name of [card] if it is referenced; a no-op (same instance) otherwise. */
    fun withCard(card: ModelCard): ModelSelection =
        if (card.id in referencedIds && cards[card.id] != card) copy(cards = cards + (card.id to card)) else this

    /** Drops cards no slot references any more, so the file never grows with every model ever tried. */
    fun pruned(): ModelSelection {
        val keep = referencedIds
        return if (cards.keys.all { it in keep }) this else copy(cards = cards.filterKeys { it in keep })
    }

    private val referencedIds: Set<String>
        get() = buildSet {
            addAll(activeByModality.values)
            addAll(lastUsedByTier.values)
            lastUsedModelId?.let(::add)
        }

}

/**
 * A model as it was last **seen** — enough to draw it, and to know which source must answer for it.
 *
 * Not a source of truth and never an input to sending: capabilities and availability belong to the resolved
 * [ChatModelSpec]. A card can name a model whose provider was deconfigured or whose weights were deleted;
 * the gate says so ([ModelGateState.Missing]) rather than acting on the card.
 */
@Serializable
data class ModelCard(
    val id: String,
    val displayName: String = id,
    /** [ProviderId.value]; a persisted record stores strings, not value classes. */
    val provider: String = "",
    /**
     * On-device weights. Tells the gate to verify it against the disk alone — a local pick must never wait
     * on (or fall back to) a cloud provider.
     */
    val local: Boolean = false,
) {
    val providerId: ProviderId get() = ProviderId(provider)

    companion object {
        fun of(spec: ModelSpec): ModelCard = ModelCard(
            id = spec.id,
            displayName = spec.displayName,
            provider = spec.provider.value,
            local = ProviderCatalog.of(spec.provider).local,
        )
    }
}
