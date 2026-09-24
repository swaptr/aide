package com.sabreware.aide.ui.navigation

import java.io.File
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable

/**
 * Every navigation route carries **primitive arguments only**.
 *
 * This is not a style rule. The JetBrains multiplatform navigation library (`org.jetbrains.androidx
 * .navigation`) has no reflection-based enum `NavType`, so a route with an enum — or any other rich type —
 * argument compiles everywhere and works on Android, then crashes at runtime on Desktop and iOS with
 * `could not find any NavType … typeMap was {}`. A platform-specific runtime crash that the compiler, the
 * Android emulator and code review all wave through is exactly the kind of thing a test has to hold.
 *
 * Discipline holds today — every route carries `String`, `Boolean` or a nullable `String`, and
 * `ModelRoute.AddModels(modality: String)` shows the enum-as-String workaround applied deliberately. This
 * is the insurance on the NEXT route, and it costs one scan of the classpath.
 *
 * The routes are found by scanning rather than listed, because a list is the thing that goes stale: a route
 * someone forgot to add would be exactly the one that breaks.
 */
class RouteArgShapeTest {

    @Test
    fun `every serializable route carries only primitive arguments`() {
        val routes = serializableRouteClasses()
        assertTrue(
            routes.isNotEmpty(),
            "Found no @Serializable route classes — the scan is broken, which would make this test vacuous.",
        )

        val offenders = routes.flatMap { route ->
            route.declaredConstructors.flatMap { constructor ->
                constructor.parameterTypes
                    .filterNot { it.isNavSafe() }
                    // Synthetic parameters, not route arguments: Kotlin's default-arguments constructor
                    // takes a bit mask plus a DefaultConstructorMarker, and the serialization plugin's
                    // deserialization constructor takes a SerializationConstructorMarker.
                    .filterNot { it.name in SYNTHETIC_PARAMETERS }
                    .map { "${route.name}(${it.name})" }
            }
        }

        assertTrue(
            offenders.isEmpty(),
            buildString {
                appendLine("${offenders.size} route argument(s) are not navigation-safe:")
                offenders.distinct().forEach { appendLine("  - $it") }
                appendLine()
                appendLine("Routes must carry String / Boolean / Int / Long and their nullable forms ONLY.")
                appendLine("An enum argument compiles and works on Android, then crashes on Desktop and iOS")
                appendLine("with 'could not find any NavType'. Pass `enum.name` and resolve it inside the page;")
                appendLine("resolve a rich domain type (a ModelSpec, a Chat) from its id String.")
            },
        )
    }

    /** Types the multiplatform NavType table can actually round-trip through a route string. */
    private fun Class<*>.isNavSafe(): Boolean = when (name) {
        "java.lang.String",
        "boolean", "java.lang.Boolean",
        "int", "java.lang.Integer",
        "long", "java.lang.Long",
        "float", "java.lang.Float",
        -> true
        else -> false
    }

    /**
     * Every `@Serializable` class under our own packages whose name marks it as a route. Scans the test's
     * own classpath roots so a newly added route is picked up with no list to maintain.
     */
    private fun serializableRouteClasses(): List<Class<*>> =
        classpathRoots()
            .flatMap { root -> classNamesIn(root) }
            .filter { it.startsWith(OUR_PACKAGE) }
            .filter { name -> ROUTE_MARKERS.any { marker -> name.contains(marker) } }
            .distinct()
            .mapNotNull { name -> runCatching { Class.forName(name, false, javaClass.classLoader) }.getOrNull() }
            .filter { it.isAnnotationPresent(Serializable::class.java) }
            // A route is a data holder; the sealed parent itself has no arguments to check.
            .filterNot { it.isInterface }

    private fun classpathRoots(): List<File> =
        System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .map(::File)
            .filter { it.exists() }

    private fun classNamesIn(root: File): List<String> = when {
        root.isDirectory -> root.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { it.relativeTo(root).invariantSeparatorsPath.removeSuffix(".class").replace('/', '.') }
            .toList()
        root.name.endsWith(".jar") -> runCatching {
            JarFile(root).use { jar ->
                jar.entries().asSequence()
                    .filter { it.name.endsWith(".class") }
                    .map { it.name.removeSuffix(".class").replace('/', '.') }
                    .toList()
            }
        }.getOrDefault(emptyList())
        else -> emptyList()
    }

    private companion object {
        const val OUR_PACKAGE = "com.sabreware.aide."

        /**
         * Where routes live. Narrow on purpose: scanning every `@Serializable` class in the app would drag
         * in the wire models, whose argument types are a different contract entirely.
         */
        val ROUTE_MARKERS = listOf("Route", "Graph")

        val SYNTHETIC_PARAMETERS = setOf(
            "kotlin.jvm.internal.DefaultConstructorMarker",
            "kotlinx.serialization.internal.SerializationConstructorMarker",
        )
    }
}
