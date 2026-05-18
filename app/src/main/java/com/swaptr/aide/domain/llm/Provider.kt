package com.swaptr.aide.domain.llm

import com.swaptr.aide.data.catalog.ProviderId

interface Provider {
    val id: ProviderId
    val engine: LlmEngine
    val management: ProviderManagement
}
