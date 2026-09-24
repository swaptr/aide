package com.sabreware.aide.core.common.persist

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentStoreTest {

    @Serializable
    data class Doc(val name: String = "", val count: Int = 0)

    @Serializable
    data class DocV2(val name: String = "", val count: Int = 0, val added: List<String> = emptyList())

    private val fs = FakeFileSystem()
    private var seq = 0

    // DataStore refuses two live stores on one path in a process, so every store here gets its own file.
    private fun freshPath(): Path = "/docs/case${seq++}/doc.json".toPath()

    private fun <T> TestScope.store(doc: PersistedDocument<T>, path: Path) =
        DocumentStore(doc, fs, path, backgroundScope, now = { 42L })

    private fun doc(durability: Durability = Durability.Intent) =
        PersistedDocument("doc", 1, Doc.serializer(), Doc(), durability)

    @Test
    fun `no file reads the default`() = runTest {
        assertEquals(Doc(), store(doc(), freshPath()).awaitReady())
    }

    @Test
    fun `update round trips through the file in an envelope`() = runTest {
        val path = freshPath()
        val store = store(doc(), path)
        store.update { it.copy(name = "gemma", count = 2) }
        assertEquals(Doc("gemma", 2), store.awaitReady())
        val text = fs.read(path) { readUtf8() }
        assertTrue("\"version\": 1" in text, "the file names its schema version")
        assertTrue("\"gemma\"" in text)
    }

    @Test
    fun `a file written before a field was added still reads, the new field defaulted`() = runTest {
        val path = freshPath()
        fs.createDirectories(path.parent!!)
        fs.write(path) { writeUtf8("""{"version":1,"data":{"name":"kept","count":3}}""") }
        val v2 = PersistedDocument("doc", 2, DocV2.serializer(), DocV2(), Durability.Intent)
        assertEquals(DocV2("kept", 3, emptyList()), store(v2, path).awaitReady())
    }

    @Test
    fun `a file from a newer build with unknown fields still reads`() = runTest {
        val path = freshPath()
        fs.createDirectories(path.parent!!)
        fs.write(path) { writeUtf8("""{"version":9,"data":{"name":"n","count":1,"future":{"x":1}}}""") }
        assertEquals(Doc("n", 1), store(doc(), path).awaitReady())
    }

    @Test
    fun `corrupt intent document resets and keeps a copy of what was there`() = runTest {
        val path = freshPath()
        fs.createDirectories(path.parent!!)
        fs.write(path) { writeUtf8("{not json") }
        val store = store(doc(Durability.Intent), path)
        // The corruption handler runs on the first read OR write; a write proves the reset path end to end.
        store.update { it.copy(count = 1) }
        assertEquals(Doc(count = 1), store.awaitReady())
        val backup = path.parent!! / "doc.corrupt-42.json"
        assertEquals("{not json", fs.read(backup) { readUtf8() })
    }

    @Test
    fun `corrupt cache document resets without a copy`() = runTest {
        val path = freshPath()
        fs.createDirectories(path.parent!!)
        fs.write(path) { writeUtf8("[]") }
        val store = store(doc(Durability.Cache), path)
        store.update { it.copy(count = 5) }
        assertEquals(Doc(count = 5), store.awaitReady())
        assertEquals(listOf(path), fs.list(path.parent!!))
    }

    /**
     * A file the process cannot read (here: a directory where the file should be — a real IOException, not
     * corruption) must not end the stream, and must only stand the default in where it cannot be mistaken
     * for an answer.
     */
    @Test
    fun `an unreadable file stands in the default only where that is allowed`() = runTest {
        val harmless = freshPath().also { fs.createDirectories(it) }
        assertEquals(Doc(), store(doc(), harmless).awaitReady())

        val answerLike = freshPath().also { fs.createDirectories(it) }
        val strict = PersistedDocument("doc", 1, Doc.serializer(), Doc(), Durability.Intent, defaultWhileUnreadable = false)
        val store = store(strict, answerLike)
        testScheduler.advanceTimeBy(1_000)
        assertEquals(DocState.Loading, store.state.value, "an empty default here would read as a settled answer")
    }

    @Test
    fun `ledger renders a changed shape differently and flags an unbumped change`() {
        assertTrue(SchemaLedger.render(Doc.serializer().descriptor) != SchemaLedger.render(DocV2.serializer().descriptor))

        val schemas = "/schemas".toPath()
        val v1 = doc()
        // First run records the entry and asks for a commit.
        assertEquals(1, SchemaLedger.verify(fs, schemas, listOf(v1)).size)
        assertTrue(SchemaLedger.verify(fs, schemas, listOf(v1)).isEmpty())
        // Same name + version, different class: must be refused.
        val drifted = PersistedDocument("doc", 1, DocV2.serializer(), DocV2(), Durability.Intent)
        assertTrue(SchemaLedger.verify(fs, schemas, listOf(drifted)).single().contains("Bump `version` to 2"))
    }
}
