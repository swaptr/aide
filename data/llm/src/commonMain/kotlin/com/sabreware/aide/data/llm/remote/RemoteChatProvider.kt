package com.sabreware.aide.data.llm.remote

import com.sabreware.aide.core.domain.llm.ChatProvider
import com.sabreware.aide.core.domain.llm.LlmEngine
import com.sabreware.aide.core.domain.llm.Manageable
import com.sabreware.aide.core.domain.llm.ProviderManagement
import com.sabreware.aide.core.domain.model.ProviderId

/**
 * One connection's `:aisdk`-backed chat: a chat engine plus a refreshable catalog, filling both the chat and
 * the manageable slot of its [com.sabreware.aide.core.domain.connection.ConnectionRuntime]. [id] is the
 * connection's. Built by a vendor per connection, never bound in the container, so no per-vendor subclass
 * is needed to keep Koin definitions apart.
 */
class RemoteChatProvider(
    override val id: ProviderId,
    override val chat: LlmEngine,
    override val management: ProviderManagement,
) : ChatProvider, Manageable
