package com.sabreware.aide.desktop.di

import androidx.lifecycle.SavedStateHandle
import com.sabreware.aide.core.domain.download.AssetKind
import com.sabreware.aide.core.domain.image.ImageProviderRegistry
import com.sabreware.aide.core.domain.llm.ChatProviderRegistry
import com.sabreware.aide.core.domain.speech.SpeechProviderRegistry
import com.sabreware.aide.core.domain.tools.ToolCategory
import com.sabreware.aide.core.domain.tools.ToolsetRegistry
import com.sabreware.aide.core.domain.download.AssetSourceRegistry
import com.sabreware.aide.core.domain.connection.ConnectionRuntimes
import com.sabreware.aide.core.domain.connection.VendorRegistry
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.provider.ProviderRegistry
import com.sabreware.aide.data.net.KtorClientFactory
import com.sabreware.aide.desktop.storage.DesktopAppDirs
import io.ktor.client.engine.okhttp.OkHttp
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.context.stopKoin
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * **What is in each registry**, which the graph test cannot see.
 *
 * `DesktopKoinGraphTest` walks the definitions that exist and resolves them, so it catches a missing
 * dependency. It cannot catch a binding dropped from the module list entirely: the sweep only visits
 * definitions that are there, so the registry just gets shorter and every remaining entry still resolves.
 * A capability silently vanishing from a platform is precisely the failure the contribution pattern
 * replaced hand-written per-platform maps to avoid — and it would look like nothing at all here.
 *
 * So the expected membership is written down. Adding a capability means adding it to a list in this file,
 * once, and that is the point: the diff says "this target gained image generation", and a diff that removes
 * a line nobody meant to remove is a failing test rather than a missing feature.
 */
class DesktopRegistryMembershipTest {

    @BeforeTest
    fun setUp() {
        KtorClientFactory.engineProvider = { OkHttp.create() }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    /**
     * A scratch root for everything this graph writes. `env = { null }` is load-bearing on Linux: a set
     * `XDG_DATA_HOME` outranks the home directory, so redirecting home alone would still land the Room DB
     * and the secret store in the developer's real data directory.
     */
    private fun scratchDirs() = DesktopAppDirs(
        env = { null },
        home = Files.createTempDirectory("aide-registry-test").toString(),
    )

    private fun koin() = koinApplication {
        allowOverride(false)
        // Same shape as DesktopKoinGraphTest: a SavedStateHandle is supplied by the ViewModel factory at
        // creation time, never by Koin, so the graph needs it stubbed to be constructible.
        modules(desktopModules(scratchDirs()) + module { factory { SavedStateHandle() } })
    }.koin

    @Test
    fun `the desktop vendors are the four cloud APIs`() {
        // Every cloud provider is a user connection of one of these. A vendor is code, contributed in `:di`
        // for both targets — so this set is the same on Android.
        assertEquals(
            setOf("openai", "anthropic", "gemini", "elevenlabs"),
            koin().get<VendorRegistry>().descriptors.map { it.id.value }.toSet(),
        )
    }

    @Test
    fun `every vendor offers a connectable service, and speech is reachable`() {
        val vendors = koin().get<VendorRegistry>()
        vendors.descriptors.forEach { vendor ->
            assertTrue(vendor.services.isNotEmpty(), "${vendor.id.value} has nothing to connect")
        }
        // A capability nobody can connect is an affordance with nothing behind it.
        listOf(Modality.Chat, Modality.Asr, Modality.Tts, Modality.Image).forEach { modality ->
            assertTrue(vendors.servicesFor(modality).isNotEmpty(), "no service serves ${modality.value}")
        }
    }

    @Test
    fun `a fresh install has no connections, so the registries are just the contributed providers`() = runBlocking<Unit> {
        val koin = koin()

        // Null until the document is read, then empty — never a seeded account.
        val runtimes = withTimeout(READ_TIMEOUT_MS) { koin.get<ConnectionRuntimes>().runtimes.filterNotNull().first() }
        assertEquals(emptyList(), runtimes)

        // No local chat provider: LiteRT is Android-only and simply not on this target's classpath. That
        // absence IS the gating mechanism — if it ever shows up here, the module graph grew an edge it should
        // not have.
        assertEquals(emptySet(), koin.get<ChatProviderRegistry>().settledIds())
        // The Android system engines live in `:app`; Sherpa is JVM-shared and does ship here.
        assertEquals(setOf("sherpa"), koin.get<SpeechProviderRegistry>().settledIds())
        assertEquals(emptySet(), koin.get<ImageProviderRegistry>().settledIds())
    }

    private suspend fun ProviderRegistry<*>.settledIds(): Set<String> =
        withTimeout(READ_TIMEOUT_MS) { flow.filterNotNull().first() }.map { it.id.value }.toSet()

    @Test
    fun `the desktop toolsets are exactly the portable ones`() {
        // Every device toolset (calendar, contacts, phone, clock, clipboard, reminders) lives in
        // `:platform:android:tools`. What is left is what runs anywhere.
        assertEquals(
            setOf(
                ToolCategory.Time,
                ToolCategory.Math,
                ToolCategory.Web,
                ToolCategory.Filesystem,
                ToolCategory.Image,
            ),
            koin().get<ToolsetRegistry>().categories.toSet(),
        )
    }

    @Test
    fun `both downloadable asset kinds are registered`() {
        assertEquals(
            setOf(AssetKind.MODEL, AssetKind.SPEECH),
            koin().get<AssetSourceRegistry>().all.map { it.kind }.toSet(),
        )
    }

    private companion object {
        const val READ_TIMEOUT_MS = 10_000L
    }
}
