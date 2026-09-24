package com.sabreware.aide.core.domain.provider

import com.sabreware.aide.core.domain.llm.Provider
import com.sabreware.aide.core.domain.model.ProviderId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProviderRegistryTest {

    private class Stub(override val id: ProviderId) : Provider

    // Cloud providers are user connections; their ids are connection ids.
    private val openai = Stub(ProviderId("openai-test01"))
    private val gemini = Stub(ProviderId("gemini-test02"))
    private val anthropicId = ProviderId("anthropic-test03")
    private val local = Stub(ProviderId.LOCAL)

    @Test
    fun `looks providers up by id and by raw key`() {
        val registry = ProviderRegistry(listOf(openai, gemini))

        assertSame(openai, registry[openai.id])
        assertSame(gemini, registry[gemini.id.value])
        assertNull(registry[anthropicId])
    }

    @Test
    fun `preserves contribution order so a ladder can walk it`() {
        val registry = ProviderRegistry(listOf(gemini, openai))

        assertEquals(listOf(gemini.id, openai.id), registry.ids.toList())
    }

    @Test
    fun `an empty contribution set is a legal registry, not a crash`() {
        assertTrue(ProviderRegistry<Provider>(emptyList()).isEmpty())
    }

    // Two bindings claiming one id would otherwise let the last one silently win, which is exactly the
    // kind of quiet wiring bug the registry exists to remove.
    @Test
    fun `rejects two providers claiming the same id`() {
        assertFailsWith<IllegalArgumentException> {
            ProviderRegistry(listOf(openai, Stub(openai.id)))
        }
    }

    @Test
    fun `static duplicates are rejected even when a dynamic set is supplied`() {
        assertFailsWith<IllegalArgumentException> {
            ProviderRegistry(listOf(local, Stub(ProviderId.LOCAL)), MutableStateFlow<List<Provider>?>(listOf(openai)))
        }
    }

    @Test
    fun `require names what IS registered so a wiring bug reads as one`() {
        val message = assertFailsWith<IllegalStateException> {
            ProviderRegistry(listOf(openai)).require(anthropicId)
        }.message.orEmpty()

        assertTrue(anthropicId.value in message, message)
        assertTrue(openai.id.value in message, message)
    }

    // ── Dynamic (connection) providers ─────────────────────────────────────────────────────────────────

    @Test
    fun `a registry with no dynamic source is settled`() {
        assertTrue(ProviderRegistry(listOf(local)).isSettled)
    }

    @Test
    fun `unread connections leave the registry unsettled and hide only the dynamic half`() = runTest {
        val dynamic = MutableStateFlow<List<Provider>?>(null)
        val registry = ProviderRegistry(listOf<Provider>(local), dynamic)

        assertFalse(registry.isSettled)
        assertEquals(listOf<Provider>(local), registry.all)
        assertSame(local, registry[ProviderId.LOCAL])
        assertNull(registry[openai.id])
        assertNull(registry.flow.first(), "flow is null until the connections are read")
    }

    @Test
    fun `connections join after the static providers and follow the dynamic set`() = runTest {
        val dynamic = MutableStateFlow<List<Provider>?>(listOf(openai))
        val registry = ProviderRegistry(listOf<Provider>(local), dynamic)

        assertTrue(registry.isSettled)
        assertEquals(listOf(ProviderId.LOCAL, openai.id), registry.ids.toList())
        assertSame(openai, registry[openai.id.value])
        assertEquals(listOf<Provider>(local, openai), registry.flow.first())

        dynamic.value = listOf(gemini)
        assertNull(registry[openai.id], "a removed connection is gone")
        assertSame(gemini, registry[gemini.id])
    }

    @Test
    fun `await suspends until the connections are read`() = runTest {
        val dynamic = MutableStateFlow<List<Provider>?>(null)
        val registry = ProviderRegistry(listOf<Provider>(local), dynamic)

        val pending = async(start = CoroutineStart.UNDISPATCHED) { registry.await(openai.id) }
        runCurrent()
        assertFalse(pending.isCompleted, "an unread connection must not answer 'gone'")

        dynamic.value = listOf(openai)
        runCurrent()
        assertSame(openai, pending.await())
    }

    @Test
    fun `await answers a static provider without waiting, and null for a truly absent id`() = runTest {
        val dynamic = MutableStateFlow<List<Provider>?>(null)
        val registry = ProviderRegistry(listOf<Provider>(local), dynamic)

        assertSame(local, registry.await(ProviderId.LOCAL))

        dynamic.value = emptyList()
        assertNull(registry.await(anthropicId))
    }
}
