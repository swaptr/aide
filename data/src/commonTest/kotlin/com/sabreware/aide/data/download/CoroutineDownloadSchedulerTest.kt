package com.sabreware.aide.data.download

import com.sabreware.aide.core.domain.download.AssetHandle
import com.sabreware.aide.core.domain.download.AssetKind
import com.sabreware.aide.core.domain.download.AssetSource
import com.sabreware.aide.core.domain.download.AssetSourceRegistry
import com.sabreware.aide.core.domain.download.DownloadAsset
import com.sabreware.aide.core.domain.download.DownloadStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * The portable download scheduler: queue → progress → complete, and what each way of stopping leaves behind.
 *
 * It had no tests, and it is the only download path desktop has. Two of the properties below are load-bearing
 * rather than incidental:
 *
 *  - **Resolution happens inside the job.** Resolving an asset asks storage where the bytes go and creates
 *    the directory they land in — disk work that used to run on whichever thread called `enqueue`. `Queued`
 *    is published before it, so the row is right while it happens.
 *  - **On-disk state is authoritative.** A file that exists outranks anything this scheduler remembers,
 *    because its own memory resets on restart and the file does not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineDownloadSchedulerTest {

    private val fs = FakeFileSystem()
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    private val asset = DownloadAsset(
        handle = AssetHandle(AssetKind.MODEL, "m1"),
        displayName = "Model One",
        downloadUrl = "https://example.invalid/m1.bin",
        partFile = "/dl/m1.bin.part".toPath(),
        finalFile = "/dl/m1.bin".toPath(),
        sizeBytes = BODY.size.toLong(),
    )

    /** A source over the fake filesystem; `installed` is a real file check, as the production one is. */
    private inner class FakeSource : AssetSource {
        override val kind: String = AssetKind.MODEL
        var resolveCalls = 0
            private set
        var deleted = false
            private set

        override suspend fun resolve(id: String): DownloadAsset? {
            resolveCalls++
            return asset.takeIf { id == asset.handle.id }
        }

        override suspend fun verify(asset: DownloadAsset): Boolean = fs.exists(asset.finalFile)
        override suspend fun isInstalled(asset: DownloadAsset): Boolean =
            fs.exists(asset.finalFile) && !fs.exists(asset.partFile)

        override suspend fun delete(asset: DownloadAsset) {
            deleted = true
            runCatching { fs.delete(asset.partFile) }
            runCatching { fs.delete(asset.finalFile) }
        }

        override val changes: Flow<Unit> get() = this@CoroutineDownloadSchedulerTest.changes
    }

    /**
     * A scheduler on REAL dispatchers.
     *
     * Not virtual time: the download runs through Ktor, which hops to dispatchers of its own, so
     * `advanceUntilIdle()` returns while the transfer is still in flight and every assertion after it reads
     * a half-finished state. The tests below wait for a terminal status instead, which is also closer to
     * what the caller does.
     */
    private fun scheduler(
        scope: CoroutineScope,
        source: FakeSource,
        engine: MockEngine,
    ): CoroutineDownloadScheduler {
        fs.createDirectories("/dl".toPath())
        return CoroutineDownloadScheduler(
            engine = DownloadEngine(fs, Dispatchers.Default, HttpClient(engine)),
            sources = AssetSourceRegistry(listOf(source)),
            scope = scope,
            ioDispatcher = Dispatchers.Default,
        )
    }

    /**
     * The status this download settles on, or a failure if it never settles.
     *
     * The `withContext` is not decoration: inside `runTest`, `withTimeout` measures VIRTUAL time, which
     * never advances while the transfer runs on real threads — so the timeout would fire instantly on work
     * that is progressing perfectly well. Hopping off the test dispatcher makes the deadline a real one.
     */
    private suspend fun CoroutineDownloadScheduler.awaitTerminal(id: String): DownloadStatus =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(TERMINAL_TIMEOUT) {
                observe(AssetKind.MODEL, id).first {
                    it is DownloadStatus.Completed || it is DownloadStatus.Failed
                }
            }
        }

    private fun okEngine() = MockEngine { request ->
        respond(
            content = BODY,
            status = HttpStatusCode.OK,
            headers = headersOf("Content-Length" to listOf(BODY.size.toString())),
        )
    }

    @Test
    fun `a queued download runs to completion and lands the final file`() = runTest {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val source = FakeSource()
        val scheduler = scheduler(scope, source, okEngine())

        scheduler.enqueue(AssetKind.MODEL, "m1")

        assertEquals(DownloadStatus.Completed("m1"), scheduler.awaitTerminal("m1"))
        assertTrue(fs.exists(asset.finalFile), "the finished bytes land at the final path")
        assertTrue(!fs.exists(asset.partFile), "the .part is renamed away, not left beside it")
        assertEquals(BODY.size.toLong(), fs.metadata(asset.finalFile).size)
        scope.cancel()
    }

    /**
     * On virtual time on purpose: the point is that `enqueue` returns having published `Queued` and having
     * done NO disk work, which is only observable if nothing has been allowed to run yet.
     *
     * The held response is load-bearing, and its absence was a real bug in this test rather than flakiness.
     * Reading the status through `observe(...).first()` SUSPENDS, and under `StandardTestDispatcher` a
     * suspension is exactly the opening the queued job needs: it would resolve, download through MockEngine
     * and land the file, so the assertion read `Completed` whenever the scheduler ran the job first.
     *
     * Holding the HTTP response — rather than the resolution — is what makes it deterministic. `observe`
     * resolves the asset too, so a gate inside `resolve` deadlocks the observation instead of the job.
     */
    @Test
    fun `enqueue publishes Queued before it touches the disk`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = FakeSource()
        fs.createDirectories("/dl".toPath())
        val holdResponse = CompletableDeferred<Unit>()
        // The scheduler gets its OWN scope, on the same virtual clock but not a child of the test's, so a
        // job still parked at the end fails nothing — `runTest` waits for every child of the test scope.
        val schedulerScope = CoroutineScope(dispatcher + Job())
        val scheduler = CoroutineDownloadScheduler(
            engine = DownloadEngine(
                fs,
                dispatcher,
                HttpClient(MockEngine { holdResponse.await(); respond(BODY, HttpStatusCode.OK) }),
            ),
            sources = AssetSourceRegistry(listOf(source)),
            scope = schedulerScope,
            ioDispatcher = dispatcher,
        )

        scheduler.enqueue(AssetKind.MODEL, "m1")

        assertEquals(
            0,
            source.resolveCalls,
            "resolving asks storage where the bytes go and creates the directory — disk work, which belongs " +
                "to the job and not to whichever thread called enqueue",
        )
        assertEquals(DownloadStatus.Queued("m1"), scheduler.observe(AssetKind.MODEL, "m1").first())
        advanceUntilIdle()
        assertTrue(source.resolveCalls > 0, "and it does happen, once the job runs")
        schedulerScope.cancel()
    }

    @Test
    fun `an unresolvable id reports a failure rather than sitting on Queued forever`() = runTest {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val source = FakeSource()
        val scheduler = scheduler(scope, source, okEngine())

        scheduler.enqueue(AssetKind.MODEL, "not-a-model")

        val status = scheduler.awaitTerminal("not-a-model")
        assertTrue(status is DownloadStatus.Failed, "an id no source claims must not leave the row Queued")
        scope.cancel()
    }

    @Test
    fun `an HTTP failure surfaces as Failed and leaves no final file`() = runTest {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val source = FakeSource()
        val scheduler = scheduler(scope, source, MockEngine { respondError(HttpStatusCode.InternalServerError) })

        scheduler.enqueue(AssetKind.MODEL, "m1")

        val status = scheduler.awaitTerminal("m1")
        assertTrue(status is DownloadStatus.Failed, "a server error is a failure the row can show, not a hang")
        assertTrue(!fs.exists(asset.finalFile), "a failed download never produces a final file")
        scope.cancel()
    }

    @Test
    fun `an already-installed asset reads Completed even with no scheduler history`() = runTest {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val source = FakeSource()
        val scheduler = scheduler(scope, source, okEngine())
        // As if a previous process had downloaded it: the file exists, this scheduler has never seen it.
        fs.write(asset.finalFile) { write(BODY) }

        val status = scheduler.observe(AssetKind.MODEL, "m1").first()

        assertEquals(
            DownloadStatus.Completed("m1"),
            status,
            "on-disk state outranks the scheduler's own memory, which resets every launch",
        )
        scope.cancel()
    }

    @Test
    fun `cancel wipes the partial file`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = FakeSource()
        fs.createDirectories("/dl".toPath())
        val scheduler = CoroutineDownloadScheduler(
            engine = DownloadEngine(fs, dispatcher, HttpClient(okEngine())),
            sources = AssetSourceRegistry(listOf(source)),
            scope = this,
            ioDispatcher = dispatcher,
        )
        fs.write(asset.partFile) { write(byteArrayOf(1, 2, 3)) }

        scheduler.cancel(AssetKind.MODEL, "m1")
        advanceUntilIdle()

        assertTrue(source.deleted, "cancelling asks the source to wipe what it left on disk")
        assertTrue(!fs.exists(asset.partFile))
    }

    private companion object {
        val BODY = ByteArray(4096) { (it % 251).toByte() }

        /** Generous: these run on real dispatchers, and a hang is the failure being guarded against. */
        val TERMINAL_TIMEOUT = 30.seconds
    }
}
