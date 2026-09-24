package com.sabreware.aide.desktop.di

import androidx.lifecycle.SavedStateHandle
import com.sabreware.aide.data.net.KtorClientFactory
import com.sabreware.aide.desktop.storage.DesktopAppDirs
import io.ktor.client.engine.okhttp.OkHttp
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.context.stopKoin
import org.koin.core.error.NoDefinitionFoundException
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Resolves **every definition** in the desktop graph.
 *
 * Koin resolves lazily, so a missing binding is invisible to the compiler and to every unit test that does
 * not happen to touch it — it surfaces when a user opens the screen that needs it. That is not theoretical:
 * making the calculator toolset portable put it on this target's classpath while its `MathEvaluator`
 * implementation stayed in an Android-only module, and nothing caught it until the app was launched by hand.
 *
 * What this asserts is precise: **every dependency a definition declares has a definition to satisfy it.**
 * A definition whose dependencies all resolve but whose constructor then fails for environmental reasons
 * (no audio device, no network) counts as a pass — Koin resolves a lambda's `get()` calls before handing
 * them to the constructor, so reaching the constructor already proves the graph is complete for that entry.
 *
 * [runtimeSupplied] holds the one kind of dependency that legitimately is not in the graph: what the
 * ViewModel factory hands a ViewModel at creation time. Declaring it here rather than skipping ViewModels
 * keeps every ViewModel's *other* dependencies under the check.
 */
class DesktopKoinGraphTest {

    private lateinit var scratch: DesktopAppDirs

    @BeforeTest
    fun setUp() {
        // Everything on-device roots at DesktopAppDirs, so handing the graph a scratch one keeps the Room
        // DB, DataStore and SecureStore this test creates out of the developer's real directories. The
        // empty `env` matters: on Linux a set `XDG_DATA_HOME` outranks `home` and would put them back.
        val temp = Files.createTempDirectory("aide-graph-test").toString()
        scratch = DesktopAppDirs(env = { null }, home = temp)
        // Main.kt injects the transport before startKoin; definitions that build a client need the same.
        KtorClientFactory.engineProvider = { OkHttp.create() }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    @OptIn(KoinInternalApi::class)
    @Test
    fun `every definition in the desktop graph resolves`() {
        // allowOverride(false) mirrors Main.kt: a duplicate definition must fail the graph rather than let
        // the last one win — this test resolves BY TYPE, so it would otherwise happily resolve the winner
        // and report the graph healthy.
        val koin = koinApplication {
            allowOverride(false)
            modules(desktopModules(scratch) + runtimeSupplied)
        }.koin

        val missing = desktopModules(scratch)
            .flatMap { module -> module.mappings.values }
            .mapNotNull { factory ->
                val definition = factory.beanDefinition
                val notFound = runCatching { koin.get<Any>(definition.primaryType, definition.qualifier) }
                    .exceptionOrNull()
                    ?.let { generateSequence(it, Throwable::cause).filterIsInstance<NoDefinitionFoundException>().firstOrNull() }
                notFound?.let { "${definition.primaryType.simpleName} -> ${it.message}" }
            }
            .distinct()

        assertTrue(
            missing.isEmpty(),
            "The desktop graph has ${missing.size} unsatisfiable dependencies:\n" +
                missing.joinToString("\n") { "  - $it" },
        )
    }

    private companion object {
        /**
         * Supplied by the ViewModel factory through `CreationExtras` at creation time, never by Koin — so
         * its absence from the graph is correct, and this stub is what lets the sweep reach the rest of a
         * ViewModel's dependencies.
         */
        val runtimeSupplied = module {
            factory { SavedStateHandle() }
        }
    }
}
