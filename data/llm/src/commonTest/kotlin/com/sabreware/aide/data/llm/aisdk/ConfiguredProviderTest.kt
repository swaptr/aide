package com.sabreware.aide.data.llm.aisdk

import com.sabreware.aide.core.domain.provider.ProviderConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.flow.MutableStateFlow

class ConfiguredProviderTest {

    private class Built(val key: String?)

    @Test
    fun `the same configuration yields the same provider, a changed one a new provider`() {
        val state = MutableStateFlow<ProviderConfig?>(ProviderConfig(baseUrl = "https://x/v1", apiKey = "k1"))
        var builds = 0
        val provider = ConfiguredProvider(state) { config -> builds++; Built(config.apiKey) }

        val first = provider()
        assertSame(first, provider())
        assertEquals(1, builds)

        // A key pasted into Settings is a new value: the next call sees it, and only then is a provider built.
        state.value = ProviderConfig(baseUrl = "https://x/v1", apiKey = "k2")
        val second = provider()
        assertEquals("k2", second?.key)
        assertEquals(2, builds)
        assertSame(second, provider())
    }

    @Test
    fun `nothing configured is null without building, and a refusal is remembered too`() {
        val state = MutableStateFlow<ProviderConfig?>(null)
        var builds = 0
        val provider = ConfiguredProvider(state) { config -> builds++; config.apiKey?.let(::Built) }

        assertNull(provider())
        assertEquals(0, builds)

        state.value = ProviderConfig(baseUrl = "https://x/v1", apiKey = null)
        assertNull(provider())
        assertNull(provider())
        assertEquals(1, builds, "a configuration the builder refused is not retried until it changes")
    }
}
