package com.sabreware.aide.core.common.di

import kotlin.reflect.KClass
import org.koin.core.definition.BeanDefinition
import org.koin.core.definition.KoinDefinition
import org.koin.dsl.bind
import org.koin.dsl.override

/**
 * Bind this definition to one or more types of which there are MANY implementations, as collected by
 * `getAll<T>()`.
 *
 * Use this instead of `bind` wherever more than one definition binds the same type — chat providers, speech
 * providers, toolsets, asset sources, deferred bootstraps. Use plain `bind` when a type has exactly one
 * implementation (a port and its impl), because there the override guard is doing real work.
 *
 * ### Why this exists
 *
 * Koin indexes a definition twice: under its own type, and under each `bind` type. The second index is a
 * plain map keyed by type, so N definitions binding the same supertype all claim ONE key — and with
 * `allowOverride(false)` (which both applications set, deliberately, so a duplicate definition is a startup
 * failure rather than a silent replacement) the second module to claim it throws:
 *
 * ```
 * DefinitionOverrideException: Already existing definition for
 *   [Singleton: 'RemoteAllowlistBootstrap', binds: DeferredBootstrap] at DeferredBootstrap::_root_
 * ```
 *
 * Within one module the collision is invisible — the later entry simply overwrites the earlier one in that
 * module's own map. It only becomes fatal when a SECOND module contributes the same supertype, which is
 * exactly what an application module doing platform contribution does. That is why the desktop graph
 * resolved cleanly while Android crashed on launch: `:desktopApp` contributes no second chat provider and no
 * second bootstrap, so nothing ever competed for the key.
 *
 * `override()` — Koin's own DSL for "this replacement is deliberate" — is safe here because **that shared
 * index is never read**. `getAll<T>()` scans instances by their OWN key and filters on declared types; the supertype index
 * only serves `get<T>()`, single resolution by supertype, which is meaningless for a type with many
 * implementations and which nothing in this codebase does. `allowOverride(false)` keeps its real job:
 * catching two definitions of the same concrete type.
 */
@Suppress("UNCHECKED_CAST")
fun KoinDefinition<*>.contributes(vararg types: KClass<*>): KoinDefinition<*> =
    types.fold(this) { definition, type ->
        ((definition as KoinDefinition<Any>) bind (type as KClass<Any>)).override()
    }

/**
 * [contributes] for the `singleOf(::X) { … }` form.
 *
 * ```kotlin
 * singleOf(::ClockToolset) { contributes<Toolset>() }
 * ```
 *
 * A separate extension because that lambda's receiver is a **`BeanDefinition`**, not a `KoinDefinition` —
 * the two DSL spellings hang off different types, which is precisely how this bug survived its own first
 * fix: the `} bind Toolset::class` sites were migrated, the `singleOf(::X) { bind<Toolset>() }` sites were
 * not, and the app still crashed on launch, just later and on a different type.
 *
 * Written against the public `secondaryTypes` / `allowOverride` properties, since the `addSecondaryType`
 * helper Koin uses internally is not exported.
 */
inline fun <reified S : Any> BeanDefinition<*>.contributes() {
    secondaryTypes = secondaryTypes + S::class
    allowOverride = true
}
