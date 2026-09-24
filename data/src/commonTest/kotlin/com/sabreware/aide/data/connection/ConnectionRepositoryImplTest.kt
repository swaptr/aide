package com.sabreware.aide.data.connection

import com.sabreware.aide.core.common.persist.DocState
import com.sabreware.aide.core.common.persist.DocumentStore
import com.sabreware.aide.core.domain.connection.Connection
import com.sabreware.aide.core.domain.connection.ConnectionDocuments
import com.sabreware.aide.core.domain.connection.ConnectionDraft
import com.sabreware.aide.core.domain.connection.VendorId
import com.sabreware.aide.core.domain.fakes.FakeLabelStore
import com.sabreware.aide.core.domain.fakes.FakeModelSelectionStore
import com.sabreware.aide.core.domain.fakes.FakeSecureStore
import com.sabreware.aide.core.domain.label.LabelSubject
import com.sabreware.aide.core.domain.label.Labels
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.model.ModelSelection
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.provider.ProviderConfig
import com.sabreware.aide.data.catalog.CachedListing
import com.sabreware.aide.data.catalog.CachedModel
import com.sabreware.aide.data.catalog.RemoteCatalogCache
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionRepositoryImplTest {

    private val fs = FakeFileSystem()

    // One live DataStore per path per process (a JVM-wide registry): each test instance gets its own files.
    private val run = Random.nextLong()

    private class Fixture(
        val repo: ConnectionRepositoryImpl,
        val secrets: FakeSecureStore,
        val cache: RemoteCatalogCache,
        val labels: FakeLabelStore,
        val selection: FakeModelSelectionStore,
    )

    private fun TestScope.fixture(
        labels: FakeLabelStore = FakeLabelStore(),
        selection: FakeModelSelectionStore = FakeModelSelectionStore(),
    ): Fixture {
        val secrets = FakeSecureStore()
        val cache = RemoteCatalogCache(
            DocumentStore(RemoteCatalogCache.Document, fs, "/cache/remote_catalogs-$run.json".toPath(), backgroundScope),
        )
        val store = DocumentStore(ConnectionDocuments.Connections, fs, "/docs/connections-$run.json".toPath(), backgroundScope)
        val repo = ConnectionRepositoryImpl(store, secrets, cache, labels, selection, now = { 1_000L })
        return Fixture(repo, secrets, cache, labels, selection)
    }

    private fun openRouter(key: String? = "sk-or-1", label: String = "OpenRouter") =
        ConnectionDraft(VendorId.OPENAI_COMPATIBLE, label, "https://openrouter.ai/api/v1", key)

    private suspend fun Fixture.list(): List<Connection> =
        (repo.state.first { it is DocState.Ready } as DocState.Ready).value.list

    @Test
    fun `create stores the row and its key, and a taken name gets a number`() = runTest {
        val f = fixture()

        val first = f.repo.create(openRouter(key = "sk-or-1"))
        val second = f.repo.create(openRouter(key = "sk-or-2"))

        assertFalse(first.existing)
        assertFalse(second.existing)
        assertNotEquals(first.connection.id, second.connection.id)
        assertTrue(first.connection.id.startsWith("openai-"), "an id names its vendor")
        assertEquals(listOf("OpenRouter", "OpenRouter 2"), f.list().map { it.label })
        assertEquals("sk-or-2", f.secrets.values.value[Connection.secretKey(second.connection.id)])
        assertEquals(
            ProviderConfig("https://openrouter.ai/api/v1", "sk-or-1"),
            f.repo.config(first.connection.id).first(),
        )
    }

    @Test
    fun `a blank name is named after the vendor`() = runTest {
        val f = fixture()

        assertEquals("openai", f.repo.create(openRouter(label = "  ")).connection.label)
    }

    @Test
    fun `the same vendor, endpoint and key is the same account`() = runTest {
        val f = fixture()
        val first = f.repo.create(openRouter(key = "sk-or-1"))

        // A trailing slash and surrounding whitespace do not make it a different account.
        val again = f.repo.create(openRouter(key = " sk-or-1 ").copy(baseUrl = "https://openrouter.ai/api/v1/"))

        assertTrue(again.existing)
        assertEquals(first.connection.id, again.connection.id)
        assertEquals(1, f.list().size)
    }

    @Test
    fun `a different key is a different account`() = runTest {
        val f = fixture()
        f.repo.create(openRouter(key = "sk-or-1"))

        val other = f.repo.create(openRouter(key = "sk-or-other"))

        assertFalse(other.existing)
        assertEquals(2, f.list().size)
    }

    @Test
    fun `update changes the endpoint and key but never the name`() = runTest {
        val f = fixture()
        val id = f.repo.create(openRouter()).connection.id

        f.repo.update(id, ConnectionDraft(VendorId.OPENAI_COMPATIBLE, "Ignored", "http://localhost:11434/v1", null))

        val updated = f.list().single()
        assertEquals("OpenRouter", updated.label)
        assertEquals("http://localhost:11434/v1", updated.baseUrl)
        assertEquals(ProviderConfig("http://localhost:11434/v1", null), f.repo.config(id).first())
    }

    @Test
    fun `remove wipes the key, the cached listing, the labels and the choices that pointed at it`() = runTest {
        val f = fixture()
        val gone = f.repo.create(openRouter(key = "sk-gone")).connection.id
        val kept = f.repo.create(openRouter(key = "sk-kept")).connection.id
        val goneModel = "$gone:gpt-5"
        val keptModel = "$kept:gpt-5"

        f.cache.write(ProviderId(gone), listing("gpt-5"))
        f.cache.write(ProviderId(kept), listing("gpt-5"))
        f.labels.update {
            Labels()
                .rename(LabelSubject.connection(gone), "Old")
                .setTags(LabelSubject.model(goneModel), listOf("work"))
                .rename(LabelSubject.model(keptModel), "Keep me")
        }
        f.selection.update {
            ModelSelection(
                activeByModality = mapOf(Modality.Chat.value to goneModel, Modality.Asr.value to keptModel),
                lastUsedModelId = goneModel,
            )
        }

        f.repo.remove(gone)

        assertEquals(listOf(kept), f.list().map { it.id })
        assertNull(f.secrets.values.value[Connection.secretKey(gone)])
        assertEquals("sk-kept", f.secrets.values.value[Connection.secretKey(kept)])
        assertNull(f.repo.config(gone).first())

        assertNull(f.cache.read(ProviderId(gone), ENDPOINT))
        assertNotNull(f.cache.read(ProviderId(kept), ENDPOINT))

        val labels = f.labels.value
        assertEquals(setOf(LabelSubject.model(keptModel).key), labels.bySubject.keys)
        assertEquals(listOf("work"), labels.tags, "the vocabulary is the user's, not the connection's")

        val selection = f.selection.current()
        assertNull(selection.activeFor(Modality.Chat))
        assertEquals(keptModel, selection.activeFor(Modality.Asr))
        assertNull(selection.lastUsedModelId)
    }

    private fun listing(vararg ids: String) = CachedListing(ENDPOINT, fetchedAt = 1L, models = ids.map { CachedModel(it) })

    private companion object {
        const val ENDPOINT = "https://openrouter.ai/api/v1"
    }
}
