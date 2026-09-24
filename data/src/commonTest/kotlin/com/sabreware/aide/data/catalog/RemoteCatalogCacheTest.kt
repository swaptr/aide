package com.sabreware.aide.data.catalog

import com.sabreware.aide.core.common.persist.DocumentStore
import com.sabreware.aide.core.domain.model.ChatCapabilities
import com.sabreware.aide.core.domain.model.ProviderId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * The disk half of "a cold start resolves cloud models without a network round trip".
 *
 * Real filesystem semantics (create, overwrite, delete) with no real disk — what is under test is what
 * survives a process boundary, so a stubbed map of paths would not be testing anything.
 */
class RemoteCatalogCacheTest {

    private val fs = FakeFileSystem()

    // DataStore allows one live store per path per process, and that registry is JVM-wide: a path of its own
    // per test instance keeps tests independent.
    private val path: Path = "/cache/documents/remote_catalogs-${Random.nextLong()}.json".toPath()
    private var process: Job? = null

    /**
     * A cache as a NEW PROCESS sees it: the previous store is shut down first (its scope cancelled, releasing
     * the file) and the next one reads the disk from scratch.
     */
    private suspend fun TestScope.cache(): RemoteCatalogCache {
        process?.cancelAndJoin()
        val job = Job(backgroundScope.coroutineContext[Job])
        process = job
        val scope = CoroutineScope(backgroundScope.coroutineContext + job)
        return RemoteCatalogCache(DocumentStore(RemoteCatalogCache.Document, fs, path, scope))
    }

    private fun listing(endpoint: String, vararg ids: String) =
        CachedListing(endpoint, fetchedAt = 1_700_000_000_000L, models = ids.map { CachedModel(it) })

    @Test
    fun `a listing written by one process is read back by the next`() = runTest {
        cache().write(ANTHROPIC, listing(ENDPOINT, "claude-opus-5", "claude-sonnet-5"))

        // A second instance over the same disk is the next launch.
        val restored = cache().read(ANTHROPIC, ENDPOINT)

        assertEquals(listOf("claude-opus-5", "claude-sonnet-5"), restored?.models?.map { it.id })
        assertEquals(1_700_000_000_000L, restored?.fetchedAt, "the ORIGINAL fetch time, not the read time")
    }

    /**
     * A connection's endpoint can be edited (a laptop's Ollama moved to Ollama Cloud), so a listing
     * belongs to the base URL that produced it. Repointing must miss rather than offer the old server's
     * models under the new one's name.
     */
    @Test
    fun `repointing the base url misses`() = runTest {
        cache().write(OPENAI, listing("https://api.openai.com/v1/", "gpt-5"))

        assertNull(cache().read(OPENAI, "http://localhost:11434/v1/"))
    }

    @Test
    fun `each provider keeps its own listing`() = runTest {
        val cache = cache()
        cache.write(ANTHROPIC, listing(ENDPOINT, "claude-opus-5"))
        cache.write(GEMINI, listing(ENDPOINT, "gemini-3.6-flash"))

        assertEquals(listOf("claude-opus-5"), cache.read(ANTHROPIC, ENDPOINT)?.models?.map { it.id })
        assertEquals(listOf("gemini-3.6-flash"), cache.read(GEMINI, ENDPOINT)?.models?.map { it.id })
    }

    /** A removed connection means the models are not ours to offer, even from disk. */
    /** Two accounts of one vendor are two providers: one's listing is never offered under the other. */
    @Test
    fun `two connections of one vendor keep separate listings`() = runTest {
        val cache = cache()
        cache.write(OPENAI, listing(ENDPOINT, "gpt-5"))

        assertNull(cache.read(OPENAI_WORK, ENDPOINT))
    }

    @Test
    fun `clear forgets one provider and leaves the others`() = runTest {
        val cache = cache()
        cache.write(ANTHROPIC, listing(ENDPOINT, "claude-opus-5"))
        cache.write(GEMINI, listing(ENDPOINT, "gemini-3.6-flash"))

        cache.clear(ANTHROPIC)

        assertNull(cache.read(ANTHROPIC, ENDPOINT))
        assertEquals(listOf("gemini-3.6-flash"), cache.read(GEMINI, ENDPOINT)?.models?.map { it.id })
    }

    @Test
    fun `a missing file is a miss, not a failure`() = runTest {
        assertNull(cache().read(GEMINI, ENDPOINT))
    }

    /** Anti-brick: a truncated or hand-edited payload costs a cache miss, never an exception at startup. */
    @Test
    fun `a corrupt payload is a miss`() = runTest {
        fs.createDirectories(path.parent!!)
        fs.write(path) { writeUtf8("{\"endpoint\":") }

        assertNull(cache().read(ANTHROPIC, ENDPOINT))
    }

    /** An empty listing is not worth seeding from — the caller must treat it as "nothing cached". */
    @Test
    fun `an empty listing reads back as a miss`() = runTest {
        cache().write(OPENAI, listing(ENDPOINT))

        assertNull(cache().read(OPENAI, ENDPOINT))
    }

    /**
     * Only identity is persisted. Capabilities are re-derived from the bundled models.dev snapshot on
     * read, which is what keeps the payload tiny and stops a stale cache from freezing an old capability
     * set onto a model.
     */
    @Test
    fun `only the wire id and display name are persisted`() = runTest {
        val spec = RemoteCatalog.anthropicSpec(
            ANTHROPIC,
            "claude-opus-5",
            caps = { ChatCapabilities(toolsLocal = true, visionIn = true, maxContext = 200_000, maxOutput = 64_000) },
            displayName = "Claude Opus 5",
        )

        val listing = listOf(spec).toCachedListing(ENDPOINT, fetchedAt = 42L)

        assertEquals(listOf(CachedModel("claude-opus-5", "Claude Opus 5")), listing.models)
        assertEquals("claude-opus-5", spec.remoteName, "the WIRE id is cached, not the namespaced spec id")
        assertEquals("anthropic-test03:claude-opus-5", spec.id)
    }

    private companion object {
        val OPENAI = ProviderId("openai-test01")
        val OPENAI_WORK = ProviderId("openai-test02")
        val ANTHROPIC = ProviderId("anthropic-test03")
        val GEMINI = ProviderId("gemini-test04")
        const val ENDPOINT = "https://api.anthropic.com/v1/"
    }
}
