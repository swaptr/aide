package com.sabreware.aide.app.llm

import com.sabreware.aide.core.domain.llm.ChatProvider
import com.sabreware.aide.core.domain.llm.LlmEngine
import com.sabreware.aide.core.domain.model.ProviderId

// On-device LiteRT-LM: chat only, no remote catalog → not Manageable (no NoOp stub needed).
class LocalProvider(
    override val chat: LlmEngine,
) : ChatProvider {
    override val id: ProviderId = ProviderId.LOCAL
}
