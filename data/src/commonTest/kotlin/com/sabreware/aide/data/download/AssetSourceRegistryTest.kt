package com.sabreware.aide.data.download

import com.sabreware.aide.core.domain.download.DownloadAsset
import com.sabreware.aide.core.domain.download.AssetHandle
import com.sabreware.aide.core.domain.download.AssetSourceRegistry
import com.sabreware.aide.core.domain.download.AssetSource
import kotlinx.coroutines.test.runTest
import com.sabreware.aide.core.domain.download.AssetKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import okio.Path.Companion.toPath

/**
 * The download stack's extension point. Adding a downloadable capability must be one `AssetSource` and
 * nothing else — no second scheduler, no capability-specific download port, no per-platform stub — so what
 * is pinned here is that the registry resolves whatever was contributed, by kind.
 */
class AssetSourceRegistryTest {

    private class StubSource(override val kind: String, private val known: Set<String>) : AssetSource {
        override suspend fun resolve(id: String): DownloadAsset? =
            if (id !in known) null else DownloadAsset(
                handle = AssetHandle(kind, id),
                displayName = id,
                downloadUrl = "https://example.invalid/$id",
                partFile = "/tmp/$id.part".toPath(),
                finalFile = "/tmp/$id".toPath(),
                sizeBytes = 1L,
            )

        override suspend fun verify(asset: DownloadAsset) = true
        override suspend fun isInstalled(asset: DownloadAsset) = false
        override suspend fun delete(asset: DownloadAsset) = Unit
    }

    private val models = StubSource(AssetKind.MODEL, setOf("shared-id"))
    private val speech = StubSource(AssetKind.SPEECH, setOf("shared-id"))

    @Test
    fun `resolves a handle through the source that owns its kind`() = runTest {
        val registry = AssetSourceRegistry(listOf(models, speech))

        assertSame(models, registry.resolve(AssetHandle(AssetKind.MODEL, "shared-id"))?.first)
        assertSame(speech, registry.resolve(AssetHandle(AssetKind.SPEECH, "shared-id"))?.first)
    }

    // The reason ids are namespaced at all: an LLM "foo" and a speech "foo" must not share a work key.
    @Test
    fun `the same id under two kinds is two different assets`() = runTest {
        val registry = AssetSourceRegistry(listOf(models, speech))

        val model = registry.resolve(AssetHandle(AssetKind.MODEL, "shared-id"))!!.second
        val voice = registry.resolve(AssetHandle(AssetKind.SPEECH, "shared-id"))!!.second

        assertTrue(model.handle.uniqueWorkName != voice.handle.uniqueWorkName)
    }

    @Test
    fun `an unknown kind or id resolves to nothing rather than the wrong source`() = runTest {
        val registry = AssetSourceRegistry(listOf(models))

        assertNull(registry.resolve(AssetHandle(AssetKind.SPEECH, "shared-id")))
        assertNull(registry.resolve(AssetHandle(AssetKind.MODEL, "never-heard-of-it")))
    }

    // A new capability is one binding; two bindings claiming one kind is a wiring bug, not a last-wins.
    @Test
    fun `rejects two sources claiming the same kind`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            AssetSourceRegistry(listOf(models, StubSource(AssetKind.MODEL, emptySet())))
        }
    }

    @Test
    fun `a contributed source is simply present`() = runTest {
        val images = StubSource("image-model", setOf("img"))
        val registry = AssetSourceRegistry(listOf(models, speech, images))

        assertEquals(3, registry.all.size)
        assertSame(images, registry["image-model"])
    }
}
