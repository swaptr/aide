package com.sabreware.aide.app.di

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sabreware.aide.core.domain.download.AssetKind
import com.sabreware.aide.core.domain.image.ImageProviderRegistry
import com.sabreware.aide.core.domain.llm.ChatProviderRegistry
import com.sabreware.aide.core.domain.speech.SpeechProviderRegistry
import com.sabreware.aide.core.domain.tools.ToolCategory
import com.sabreware.aide.core.domain.tools.ToolsetRegistry
import com.sabreware.aide.core.domain.download.AssetSourceRegistry
import com.sabreware.aide.core.domain.connection.ConnectionRuntimes
import com.sabreware.aide.core.domain.connection.VendorRegistry
import com.sabreware.aide.core.domain.provider.ProviderRegistry
import java.io.File
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.stopKoin
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * **What is in each registry** on Android — the peer of `DesktopRegistryMembershipTest`, and the half of the
 * contribution contract `AndroidKoinGraphTest` structurally cannot check.
 *
 * The graph test walks the definitions that exist and resolves them. A binding dropped from the module list
 * entirely is invisible to it: the sweep only visits definitions that are there, so the registry simply gets
 * shorter and everything left still resolves. On this target that would mean the phone losing its phone
 * tools, or the local LLM provider quietly not shipping, with a green build.
 *
 * The Android/desktop pair together also state the gating rule as an assertion rather than a comment: the
 * device toolsets and the on-device chat provider are here and absent there, because those modules are in
 * this application's dependency list and not in that one.
 */
@RunWith(AndroidJUnit4::class)
class AndroidRegistryMembershipTest {

    @After
    fun tearDown() {
        stopKoin()
    }

    private fun koin() = koinApplication {
        allowOverride(false)
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        androidContext(IsolatedStorageContext(target, File(target.cacheDir, "koin-registry-test")))
        modules(appModules + module { factory { SavedStateHandle() } })
    }.koin

    @Test
    fun theVendorsAreTheFourCloudApisAndMatchDesktop() {
        // Every cloud provider is a user connection of one of these. Vendors are contributed in `:di` for
        // both targets, so desktop's peer test asserts the same set.
        assertEquals(
            setOf("openai", "anthropic", "gemini", "elevenlabs"),
            koin().get<VendorRegistry>().descriptors.map { it.id.value }.toSet(),
        )
    }

    @Test
    fun aFreshInstallHasNoConnectionsSoTheRegistriesAreTheContributedProviders() = runBlocking<Unit> {
        val koin = koin()

        // Null until the document is read, then empty — never a seeded account.
        val runtimes = withTimeout(READ_TIMEOUT_MS) { koin.get<ConnectionRuntimes>().runtimes.filterNotNull().first() }
        assertEquals(emptyList<Any>(), runtimes)

        // `local` is LiteRT, which only `:app` holds. Desktop's peer test asserts its absence there; between
        // them, the module graph IS the gate.
        assertEquals(setOf("local"), koin.get<ChatProviderRegistry>().settledIds())
        // Sherpa is JVM-shared; the system engine only Android has.
        assertEquals(setOf("sherpa", "android-system"), koin.get<SpeechProviderRegistry>().settledIds())
        // Image generation is cloud-only: every image provider is a connection's.
        assertEquals(emptySet<String>(), koin.get<ImageProviderRegistry>().settledIds())
    }

    private suspend fun ProviderRegistry<*>.settledIds(): Set<String> =
        withTimeout(READ_TIMEOUT_MS) { flow.filterNotNull().first() }.map { it.id.value }.toSet()

    @Test
    fun theAndroidToolsetsAreThePortableOnesPlusEveryDeviceToolset() {
        assertEquals(
            setOf(
                // Portable — the same five desktop has.
                ToolCategory.Time,
                ToolCategory.Math,
                ToolCategory.Web,
                ToolCategory.Filesystem,
                ToolCategory.Image,
                // Device — `:platform:android:tools` only.
                ToolCategory.Clock,
                ToolCategory.Calendar,
                ToolCategory.Phone,
                ToolCategory.Contacts,
                ToolCategory.Clipboard,
                ToolCategory.Device,
            ),
            koin().get<ToolsetRegistry>().categories.toSet(),
        )
    }

    @Test
    fun bothDownloadableAssetKindsAreRegistered() {
        assertEquals(
            setOf(AssetKind.MODEL, AssetKind.SPEECH),
            koin().get<AssetSourceRegistry>().all.map { it.kind }.toSet(),
        )
    }

    private companion object {
        const val READ_TIMEOUT_MS = 10_000L
    }

    /** The target app's Context with every on-disk root pointed at a scratch directory. */
    private class IsolatedStorageContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = subDir("files")
        override fun getCacheDir(): File = subDir("cache")
        override fun getNoBackupFilesDir(): File = subDir("no_backup")
        override fun getDir(name: String, mode: Int): File = subDir("app_$name")
        override fun getDatabasePath(name: String): File = File(subDir("databases"), name)

        private fun subDir(name: String): File = File(root, name).apply { mkdirs() }
    }
}
