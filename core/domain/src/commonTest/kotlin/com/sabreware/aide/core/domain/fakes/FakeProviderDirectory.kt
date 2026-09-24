package com.sabreware.aide.core.domain.fakes

import com.sabreware.aide.core.domain.connection.ProviderDirectory
import com.sabreware.aide.core.domain.connection.ProviderInfo
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [ProviderDirectory]: every connection's info by id, settled (empty) unless told otherwise. */
class FakeProviderDirectory(
    infos: Map<String, ProviderInfo>? = emptyMap(),
) : ProviderDirectory {
    override val connections = MutableStateFlow(infos)
}
