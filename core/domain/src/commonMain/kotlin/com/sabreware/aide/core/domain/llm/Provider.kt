package com.sabreware.aide.core.domain.llm

import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.provider.ProviderRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A registered provider — identity only. What a provider *does* is expressed by the capability
 * (role) interfaces it implements: [ChatProvider], [com.sabreware.aide.core.domain.speech.SpeechProvider],
 * [Manageable], … A provider may implement ANY combination (a multimodal vendor implements several;
 * an on-device speech engine implements only [com.sabreware.aide.core.domain.speech.SpeechProvider]).
 *
 * Each capability gets its OWN [ProviderRegistry] subtype — [ChatProviderRegistry], [ManageableRegistry],
 * [com.sabreware.aide.core.domain.speech.SpeechProviderRegistry] — all keyed by [ProviderId.value]: an
 * on-device engine's fixed id, or a user connection's id for a cloud provider. A
 * consumer injects exactly the registry it needs: no base-`Provider` collection, no `filterIsInstance`, no
 * downcast. A multimodal vendor binds its single instance once per capability and appears in each registry.
 * Adding a modality = a new sub-interface + its registry, never an edit to existing types.
 */
interface Provider {
    val id: ProviderId
}

/** Serves the chat (LLM) modality. */
interface ChatProvider : Provider {
    val chat: LlmEngine
}

/**
 * Has a remote, refreshable model catalog + credentials. Cross-cuts modality (an on-device speech
 * provider is NOT [Manageable]; a future cloud vendor that serves several modalities is). Keeping
 * this a separate trait is what lets `LocalProvider` drop its old NoOp management stub.
 */
interface Manageable : Provider {
    val management: ProviderManagement
}

/** Every chat-capable provider: the contributed on-device ones plus each connection's. */
class ChatProviderRegistry(
    providers: Collection<ChatProvider>,
    connected: StateFlow<List<ChatProvider>?> = MutableStateFlow(emptyList<ChatProvider>()),
) : ProviderRegistry<ChatProvider>(providers, connected)

/** Every provider with a refreshable remote catalog — the model registry hydrates specs through these. */
class ManageableRegistry(
    providers: Collection<Manageable>,
    connected: StateFlow<List<Manageable>?> = MutableStateFlow(emptyList<Manageable>()),
) : ProviderRegistry<Manageable>(providers, connected)
