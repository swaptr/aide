package com.swaptr.aide.domain.llm

import com.swaptr.aide.data.catalog.ProviderId

class LocalProvider(
    override val engine: LlmEngine,
) : Provider {
    override val id: ProviderId = ProviderId.LOCAL
    override val management: ProviderManagement = NoOpProviderManagement
}
