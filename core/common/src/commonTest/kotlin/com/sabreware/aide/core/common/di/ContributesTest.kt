package com.sabreware.aide.core.common.di

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.koin.core.error.DefinitionOverrideException
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * The exact failure [contributes] exists to prevent, pinned in both directions.
 *
 * This shipped as a crash on launch: `AideApp.onCreate` threw
 * `DefinitionOverrideException: Already existing definition for [Singleton: 'RemoteAllowlistBootstrap',
 * binds: DeferredBootstrap] at DeferredBootstrap::_root_`, and the desktop graph test could not see it.
 *
 * Koin indexes a definition under its own type AND under each bound type. The second index is one map keyed
 * by type, so every definition binding a shared supertype claims the SAME key. Within one module the later
 * one quietly overwrites the earlier in that module's own map; the collision only becomes fatal when a
 * SECOND module claims the key, because that is when `saveMapping` consults `allowOverride`. Both
 * applications set `allowOverride(false)` deliberately, so the contribution pattern — many providers,
 * toolsets and bootstraps binding one supertype — was a startup crash waiting for a second module.
 *
 * That is why one app crashed and the other did not: `:desktopApp` contributes no second implementation of
 * anything, `:app` contributes several.
 */
class ContributesTest {

    private interface Capability { val id: String }
    private class First : Capability { override val id = "first" }
    private class Second : Capability { override val id = "second" }
    private class Third : Capability { override val id = "third" }

    /** The bug. Two MODULES binding one supertype with plain `bind`, under the setting both apps use. */
    @Test
    fun `plain bind across two modules is fatal when overrides are refused`() {
        val moduleA = module { single { First() } bind Capability::class }
        val moduleB = module { single { Second() } bind Capability::class }

        assertFailsWith<DefinitionOverrideException> {
            koinApplication {
                allowOverride(false)
                modules(moduleA, moduleB)
            }
        }
    }

    /** The fix. Same shape, and the graph loads. */
    @Test
    fun `contributes across two modules loads cleanly`() {
        val moduleA = module { single { First() }.contributes(Capability::class) }
        val moduleB = module { single { Second() }.contributes(Capability::class) }

        val koin = koinApplication {
            allowOverride(false)
            modules(moduleA, moduleB)
        }.koin

        assertEquals(
            listOf("first", "second"),
            koin.getAll<Capability>().map { it.id }.sorted(),
            "every contribution is collected — that is the whole point of the pattern",
        )
    }

    /**
     * `getAll` must still see contributions made within a SINGLE module. This is the case that hid the bug:
     * it always worked, because the same-module collision is silent.
     */
    @Test
    fun `contributes within one module collects all of them`() {
        val single = module {
            single { First() }.contributes(Capability::class)
            single { Second() }.contributes(Capability::class)
            single { Third() }.contributes(Capability::class)
        }

        val koin = koinApplication {
            allowOverride(false)
            modules(single)
        }.koin

        assertEquals(3, koin.getAll<Capability>().size)
    }

    /**
     * **The cost of this fix, stated rather than hidden.**
     *
     * Koin's `override()` sets one flag on the definition, and `saveMapping` consults it for EVERY index that
     * definition claims — the supertype one we need relaxed, and its own primary one too. So a `contributes`
     * definition duplicated by concrete type no longer throws; the second silently replaces the first.
     *
     * That is a genuine narrowing of what `allowOverride(false)` catches, limited to contributed types. It is
     * accepted because the alternative is worse: without it the contribution pattern cannot coexist with the
     * setting at all, and the app does not start. Plain `bind` and plain `single` keep the full guard, which
     * is the majority of the graph.
     *
     * The compensating check is the per-application Koin graph test, which resolves every definition and
     * would surface a provider that vanished because a duplicate replaced it.
     */
    @Test
    fun `a duplicate of a contributed type is NOT caught — the accepted trade-off`() {
        val moduleA = module { single { First() }.contributes(Capability::class) }
        val moduleB = module { single { First() }.contributes(Capability::class) }

        val koin = koinApplication {
            allowOverride(false)
            modules(moduleA, moduleB)
        }.koin

        assertEquals(1, koin.getAll<Capability>().size, "the second definition replaced the first, silently")
    }

    /** A duplicate of a type bound WITHOUT `contributes` is still caught — the guard is narrowed, not lost. */
    @Test
    fun `a duplicate of an ordinary definition still fails`() {
        val moduleA = module { single { First() } }
        val moduleB = module { single { First() } }

        assertFailsWith<DefinitionOverrideException> {
            koinApplication {
                allowOverride(false)
                modules(moduleA, moduleB)
            }
        }
    }

    /**
     * The `singleOf(::X) { … }` spelling, which is a DIFFERENT extension point: that lambda's receiver is a
     * `BeanDefinition`, not a `KoinDefinition`. Missing it is what made the first fix incomplete — the
     * `} bind X::class` sites were converted, these were not, and the app went on crashing on launch with the
     * same exception naming a different type.
     */
    @Test
    fun `the singleOf form contributes too, and mixes with the other spelling`() {
        val moduleA = module { singleOf(::First) { contributes<Capability>() } }
        val moduleB = module { single { Second() }.contributes(Capability::class) }

        val koin = koinApplication {
            allowOverride(false)
            modules(moduleA, moduleB)
        }.koin

        assertEquals(
            listOf("first", "second"),
            koin.getAll<Capability>().map { it.id }.sorted(),
            "both DSL spellings land in the same collection",
        )
    }

    /** A definition can join several collections at once — a provider that is also `Manageable`. */
    @Test
    fun `the vararg form binds every type it is given`() {
        val marker = object {}
        val koin = koinApplication {
            allowOverride(false)
            modules(
                module { single { First() }.contributes(Capability::class) },
                module { single { Second() }.contributes(Capability::class) },
            )
        }.koin

        assertTrue(marker != null)
        assertEquals(2, koin.getAll<Capability>().size)
    }
}
