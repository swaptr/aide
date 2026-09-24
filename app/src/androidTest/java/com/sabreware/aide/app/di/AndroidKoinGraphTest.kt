package com.sabreware.aide.app.di

import com.sabreware.aide.ui.navigation.Route
import com.sabreware.aide.feature.tasks.ui.TaskRoute
import org.koin.core.parameter.parametersOf
import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.error.NoDefinitionFoundException
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Resolves **every definition** in the Android graph — the peer of `DesktopKoinGraphTest`, and instrumented
 * for the one reason that matters: half this graph needs a real `Context`, so a JVM unit test could only
 * check it behind a fake and would be checking the fake.
 *
 * Koin resolves lazily, so a missing binding is invisible to the compiler and to every test that does not
 * happen to touch it; it surfaces when a user opens the screen that needs it. This is the check that turns
 * that into a build failure.
 *
 * The assertion is precise: **every dependency a definition declares has a definition to satisfy it.** A
 * definition whose dependencies all resolve but whose constructor then fails for environmental reasons
 * counts as a pass — Koin resolves a lambda's `get()` calls before handing them to the constructor, so
 * reaching the constructor already proves the graph is complete for that entry.
 */
@RunWith(AndroidJUnit4::class)
class AndroidKoinGraphTest {

    private var realHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        // Resolving a bootstrap starts its work on APPLICATION_SCOPE, whose SupervisorJob installs no
        // handler — so a failure out there would kill the test process instead of failing this assertion.
        // Swallow those: what is under test is the graph, not what the graph does once built.
        realHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(realHandler)
    }

    @OptIn(KoinInternalApi::class)
    @Test
    fun everyDefinitionInTheAndroidGraphResolves() {
        // The target app's Context — it has the Application, the assets and the resources the real graph
        // reads — but with storage redirected, so the DataStore / Room / SecureStore this sweep creates do
        // not collide with the instances the running app holds open (DataStore permits one per file per
        // process). The instrumentation context is not usable here: it has no Application, so
        // `applicationContext` is null and half the graph NPEs.
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = IsolatedStorageContext(target, File(target.cacheDir, "koin-graph-test"))

        // A private container — the app under test has already called startKoin(), and this must not touch
        // the global one.
        val koin = koinApplication {
            // Mirrors AideApp: a duplicate definition fails here rather than silently letting the last one
            // win — this test resolves BY TYPE and would otherwise resolve the winner and call it healthy.
            allowOverride(false)
            androidContext(context)
            modules(appModules + runtimeSupplied)
        }.koin

        val missing = appModules
            .flatMap { module -> module.mappings.values }
            .mapNotNull { factory ->
                val definition = factory.beanDefinition
                // A page's route reaches its view model at runtime, from the page's entry (Navigation 3 keeps
                // route args out of SavedStateHandle); supply one of each here, matched by type.
                val notFound = runCatching {
                    koin.get<Any>(definition.primaryType, definition.qualifier) { parametersOf(Route.Chat(), TaskRoute.Detail("task"), TaskRoute.Edit()) }
                }
                    .exceptionOrNull()
                    ?.let { failure ->
                        generateSequence(failure, Throwable::cause)
                            .filterIsInstance<NoDefinitionFoundException>()
                            .firstOrNull()
                    }
                notFound?.let { "${definition.primaryType.simpleName} -> ${it.message}" }
            }
            .distinct()

        assertTrue(
            "The Android graph has ${missing.size} unsatisfiable dependencies:\n" +
                missing.joinToString("\n") { "  - $it" },
            missing.isEmpty(),
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

    /**
     * The target app's Context with every on-disk root pointed at a scratch directory. Real Application,
     * real assets; separate files.
     */
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
