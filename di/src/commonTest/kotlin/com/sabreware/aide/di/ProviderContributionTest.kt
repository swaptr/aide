package com.sabreware.aide.di

import com.sabreware.aide.core.domain.connection.Connection
import com.sabreware.aide.core.domain.connection.ConnectionRuntime
import com.sabreware.aide.core.domain.connection.Vendor
import com.sabreware.aide.core.domain.connection.VendorDescriptor
import com.sabreware.aide.core.domain.connection.VendorId
import com.sabreware.aide.core.domain.connection.VendorRegistry
import com.sabreware.aide.core.domain.llm.ChatProvider
import com.sabreware.aide.core.domain.llm.ChatProviderRegistry
import com.sabreware.aide.core.domain.llm.LlmEngine
import com.sabreware.aide.core.domain.llm.Manageable
import com.sabreware.aide.core.domain.llm.ManageableRegistry
import com.sabreware.aide.core.domain.llm.ProviderManagement
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.provider.ProviderConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.context.stopKoin
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The contribution mechanism itself, not any particular provider: `getAll<Capability>()` must see every
 * definition that `bind`s that capability, from whichever module bound it.
 *
 * This is the load-bearing assumption behind deleting the two hand-written per-platform provider maps, so it
 * is pinned here rather than left to the first runtime that notices a provider went missing.
 */
class ProviderContributionTest {

    @AfterTest fun tearDown() = stopKoin()

    // One class per contribution, as in production (LocalProvider, SherpaSpeechProvider, each Vendor): a Koin
    // definition is keyed by its primary type, so two unqualified singles of the SAME class would override
    // each other and the second would be the only one `getAll` ever sees.
    private abstract class StubChat(override val id: ProviderId) : ChatProvider {
        override val chat: LlmEngine get() = error("not exercised")
    }

    private abstract class StubManaged(id: ProviderId) : StubChat(id), Manageable {
        override val management: ProviderManagement get() = error("not exercised")
    }

    private class StubOpenAi : StubManaged(OPENAI)
    private class StubAnthropic : StubManaged(ANTHROPIC)
    private class StubLocal : StubChat(ProviderId.LOCAL)

    // Mirrors the real shape: cloud providers bound in one module (":di"), a platform-only provider bound in
    // another (":app"), and the registry declared once, in neither of them.
    private val cloud = module {
        single { StubOpenAi() } binds arrayOf(ChatProvider::class, Manageable::class)
        single { StubAnthropic() } binds arrayOf(ChatProvider::class, Manageable::class)
    }
    private val platform = module {
        single { StubLocal() } bind ChatProvider::class
    }
    private val registries = module {
        single { ChatProviderRegistry(getAll<ChatProvider>()) }
        single { ManageableRegistry(getAll<Manageable>()) }
    }

    @Test
    fun `the registry collects providers bound in other modules`() {
        val koin = koinApplication { modules(cloud, platform, registries) }.koin

        val chat = koin.get<ChatProviderRegistry>()
        assertEquals(
            setOf(OPENAI, ANTHROPIC, ProviderId.LOCAL),
            chat.ids.toSet(),
        )
    }

    // Omitting the platform module is exactly how a target drops a provider — the registry shrinks, and no
    // map anywhere had to be edited to a shorter copy.
    @Test
    fun `a target that omits a module simply has one fewer provider`() {
        val koin = koinApplication { modules(cloud, registries) }.koin

        assertEquals(setOf(OPENAI, ANTHROPIC), koin.get<ChatProviderRegistry>().ids.toSet())
    }

    @Test
    fun `a provider serving two capabilities appears in both registries as one instance`() {
        val koin = koinApplication { modules(cloud, platform, registries) }.koin

        val chat = koin.get<ChatProviderRegistry>()
        val manageable = koin.get<ManageableRegistry>()

        assertSame<Any?>(chat[OPENAI], manageable[OPENAI])
        // LOCAL binds only ChatProvider, so it is absent from the manageable registry rather than present
        // with a no-op management stub.
        assertNull(manageable[ProviderId.LOCAL])
        assertEquals(2, manageable.all.size)
    }

    // Vendors are the one cloud contribution left in the graph (connections are data): the same mechanism
    // must collect them, and two claiming one id is a wiring bug, not a silent override.
    private class StubVendor(id: String) : Vendor {
        override val descriptor = VendorDescriptor(VendorId(id), id, services = emptyList())
        override fun connect(connection: Connection, config: StateFlow<ProviderConfig?>, scope: CoroutineScope) =
            ConnectionRuntime(connection)
    }

    private class StubOpenAiVendor : Vendor by StubVendor("openai")
    private class StubGeminiVendor : Vendor by StubVendor("gemini")

    @Test
    fun `vendors bound in separate modules are collected into one registry`() {
        val koin = koinApplication {
            modules(
                module { single { StubOpenAiVendor() } bind Vendor::class },
                module { single { StubGeminiVendor() } bind Vendor::class },
                module { single { VendorRegistry(getAll<Vendor>()) } },
            )
        }.koin

        assertEquals(setOf("openai", "gemini"), koin.get<VendorRegistry>().descriptors.map { it.id.value }.toSet())
    }

    @Test
    fun `two vendors claiming one id are rejected`() {
        assertFailsWith<IllegalArgumentException> { VendorRegistry(listOf(StubVendor("openai"), StubVendor("openai"))) }
    }

    private companion object {
        // Cloud provider ids are connection ids.
        val OPENAI = ProviderId("openai-test01")
        val ANTHROPIC = ProviderId("anthropic-test02")
    }
}
