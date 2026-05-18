package com.swaptr.aide.domain.llm.ollama

import com.swaptr.aide.data.catalog.ProviderId
import com.swaptr.aide.domain.llm.LlmEngine
import com.swaptr.aide.domain.llm.Provider
import com.swaptr.aide.domain.llm.ProviderManagement

class OllamaProvider(
    override val engine: LlmEngine,
    override val management: OllamaManagement,
) : Provider {
    override val id: ProviderId = ProviderId.OLLAMA
}
