package com.sabreware.aide.data.llm.remote

import kotlin.random.Random
import kotlinx.coroutines.test.TestScope
import com.sabreware.aide.core.common.persist.DocumentStore
import com.sabreware.aide.core.domain.llm.RemoteCatalogState
import com.sabreware.aide.core.domain.llm.currentSpecs
import com.sabreware.aide.core.domain.model.ChatCapabilities
import com.sabreware.aide.core.domain.model.ChatModelSpec
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.provider.ConnectionTestResult
import com.sabreware.aide.core.domain.provider.ProviderConfig
import com.sabreware.aide.data.catalog.RemoteCatalog
import com.sabreware.aide.data.catalog.RemoteCatalogCache
import com.sabreware.aide.data.catalog.toCachedListing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * What a configured cloud provider says about itself **before the network answers**.
 *
 * This is the whole startup bug: cloud specs used to be minted only from a live `/v1/models`, and every
 * provider started at [RemoteCatalogState.Unconfigured], so a fresh process reported "no models, not
 * configured" about a provider that was set up fine. The chat header flashed "No model", the composer
 * offered "Set up a model to begin", and both the IME and assistant gates read `NoModel` — until the
 * deferred catalog fetch landed, or forever on a dead network.
 */
class RemoteProviderManagementSeedTest {

    private val fs = FakeFileSystem()

    // One live DataStore per path per process (a JVM-wide registry): each test instance gets its own file.
    private val path: Path = "/cache/documents/remote_catalogs-${Random.nextLong()}.json".toPath()

    private fun TestScope.cache() =
        RemoteCatalogCache(DocumentStore(RemoteCatalogCache.Document, fs, path, backgroundScope))

    // The connection's live endpoint + key; null is a removed connection.
    private fun configOf(config: ProviderConfig?) = MutableStateFlow(config)

    private fun caps(id: String) = ChatCapabilities(toolsLocal = true, maxContext = 128_000, maxOutput = 16_384)

    private fun management(
        scope: CoroutineScope,
        configs: MutableStateFlow<ProviderConfig?>,
        cache: RemoteCatalogCache,
        catalog: suspend () -> List<ChatModelSpec>,
    ) = RemoteProviderManagement(
        providerId = CONNECTION,
        config = configs,
        scope = scope,
        catalog = catalog,
        connectionTester = { ConnectionTestResult.Ok(0) },
        cache = cache,
        mint = { RemoteCatalog.openAiSpec(CONNECTION, it.id, ::caps) },
    )

    private fun specs(vararg ids: String) = ids.map { RemoteCatalog.openAiSpec(CONNECTION, it, ::caps) }

    /**
     * The fix, stated once: a provider that has been used before knows its models on the first frame, from
     * disk, with the catalog lambda untouched — so nothing waits on a request that has not been made yet.
     */
    @Test
    fun `a configured provider seeds its models from disk without fetching`() = runTest(UnconfinedTestDispatcher()) {
        val cache = cache()
        // A previous session's successful fetch.
        seedDisk(cache)
        var fetched = false

        val mgmt = management(
            backgroundScope, configOf(CONFIG), cache,
            catalog = { fetched = true; specs("gpt-5") },
        )

        val state = mgmt.remoteCatalogFlow.first()
        assertEquals(listOf("openai-test01:gpt-5", "openai-test01:o5-mini"), state.currentSpecs.map { it.id })
        assertTrue(state is RemoteCatalogState.Ready, "a last-good listing is an answer, not a pending fetch")
        assertEquals(FETCHED_AT, state.fetchedAt, "the ORIGINAL fetch time — the 'updated' line stays true")
        assertFalse(fetched, "construction must not put a network request on the first frame")
    }

    /**
     * The other half of the same bug. A provider with credentials and nothing cached (the run right after
     * the key is entered) is NOT unconfigured — reporting that made `isConfigured` false and the model gate
     * `NoModel` for a provider that works.
     */
    @Test
    fun `a configured provider with nothing cached is refreshing, not unconfigured`() = runTest(UnconfinedTestDispatcher()) {
        val mgmt = management(backgroundScope, configOf(CONFIG), cache(), catalog = { specs("gpt-5") })

        assertTrue(mgmt.remoteCatalogFlow.first() is RemoteCatalogState.Refreshing)
    }

    @Test
    fun `no credentials is still unconfigured`() = runTest(UnconfinedTestDispatcher()) {
        val mgmt = management(backgroundScope, configOf(null), cache(), catalog = { specs("gpt-5") })

        assertEquals(RemoteCatalogState.Unconfigured, mgmt.remoteCatalogFlow.first())
    }

    /** A fetch is what fills the cache, so the NEXT launch has something to seed from. */
    @Test
    fun `a successful refresh is what the next launch reads`() = runTest(UnconfinedTestDispatcher()) {
        val cache = cache()
        val configs = configOf(CONFIG)
        management(backgroundScope, configs, cache, catalog = { specs("gpt-5", "o5-mini") })
            .refreshCatalog()

        val next = management(backgroundScope, configs, cache, catalog = { error("must not fetch") })

        assertEquals(listOf("openai-test01:gpt-5", "openai-test01:o5-mini"), next.remoteCatalogFlow.first().currentSpecs.map { it.id })
    }

    /** An unreachable endpoint must not empty the picker or drop the chat header back to "No model". */
    @Test
    fun `a failed refresh keeps what was seeded from disk`() = runTest(UnconfinedTestDispatcher()) {
        val cache = cache()
        seedDisk(cache)
        val mgmt = management(backgroundScope, configOf(CONFIG), cache, catalog = { error("offline") })

        mgmt.refreshCatalog()

        val state = mgmt.remoteCatalogFlow.first()
        assertTrue(state is RemoteCatalogState.Failed)
        assertEquals(listOf("openai-test01:gpt-5", "openai-test01:o5-mini"), state.currentSpecs.map { it.id })
    }

    /** A removed connection's config goes null: its listing must not outlive it on disk. */
    @Test
    fun `a removed connection forgets the cached listing`() = runTest(UnconfinedTestDispatcher()) {
        val cache = cache()
        seedDisk(cache)
        val configs = configOf(CONFIG)

        val mgmt = management(backgroundScope, configs, cache, catalog = { specs("gpt-5") })
        assertTrue(mgmt.remoteCatalogFlow.first() is RemoteCatalogState.Ready)

        configs.value = null

        assertEquals(RemoteCatalogState.Unconfigured, mgmt.remoteCatalogFlow.first())
        assertNull(cache.read(CONNECTION, BASE_URL))
    }

    /** A pasted key is a change after the first read: it refetches, which is how a wrong key gets fixed. */
    @Test
    fun `a new key refetches`() = runTest(UnconfinedTestDispatcher()) {
        val cache = cache()
        seedDisk(cache)
        val configs = configOf(CONFIG)
        var fetches = 0
        val mgmt = management(backgroundScope, configs, cache, catalog = { fetches++; specs("gpt-6") })

        configs.value = CONFIG.copy(apiKey = "sk-new")

        assertEquals(1, fetches)
        assertEquals(listOf("openai-test01:gpt-6"), mgmt.remoteCatalogFlow.first().currentSpecs.map { it.id })
    }

    /**
     * A connection's endpoint can be edited, so a listing is only valid for the base URL that produced it — a repointed endpoint seeds nothing rather than the old
     * server's models.
     */
    @Test
    fun `a repointed base url does not seed`() = runTest(UnconfinedTestDispatcher()) {
        val cache = cache()
        seedDisk(cache)
        val configs = configOf(CONFIG.copy(baseUrl = "http://localhost:11434/v1/"))

        val mgmt = management(backgroundScope, configs, cache, catalog = { specs("llama4") })

        assertTrue(mgmt.remoteCatalogFlow.first() is RemoteCatalogState.Refreshing)
    }

    // A previous session's successful fetch, recorded exactly as `refreshInternal` records one — with a
    // pinned timestamp, so the assertion above is about the CACHED time rather than the clock.
    private suspend fun seedDisk(cache: RemoteCatalogCache) {
        cache.write(CONNECTION, specs("gpt-5", "o5-mini").toCachedListing(BASE_URL, FETCHED_AT))
    }

    private companion object {
        val CONNECTION = ProviderId("openai-test01")
        const val BASE_URL = "https://api.openai.com/v1/"
        const val FETCHED_AT = 1_700_000_000_000L
        val CONFIG = ProviderConfig(baseUrl = BASE_URL, apiKey = "sk-test")
    }
}
