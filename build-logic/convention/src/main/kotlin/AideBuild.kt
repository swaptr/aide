import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/**
 * Build constants shared by every convention plugin. The SDK levels and the JVM target are declared once
 * here instead of being repeated in each module's build file (see ARCHITECTURE.md §5, step 1).
 */
object AideBuild {
    const val COMPILE_SDK = 36
    const val MIN_SDK = 35
    const val TARGET_SDK = 36

    /**
     * The JDK every module compiles with. Pinned so the build does not silently depend on whichever JDK
     * launched Gradle — 17 locally and 21 in CI produced different bytecode from the same sources.
     */
    const val JVM_TOOLCHAIN = 21

    /**
     * The shared test fakes, which live next to the interfaces they fake in `:core:domain`'s own
     * commonTest. The AGP-9 KMP-library plugin exports no test fixtures, so a module that wants them points
     * at this ONE directory rather than copying it.
     *
     * Named here so the four modules that use it do not restate the path, but declared per-module rather
     * than in a convention plugin: which fixtures a module wants is its own choice, and wiring it for
     * everyone would hand `:core:common` — which must depend on nothing of ours — a dependency on
     * `:core:domain`.
     */
    const val SHARED_FAKES = "core/domain/src/commonTest/kotlin/com/sabreware/aide/core/domain/fakes"

    /** The root package every module's namespace and package path hangs off. */
    const val PACKAGE = "com.sabreware.aide"

    // The :aisdk family's published version — one place, read by aide.aisdk.library's publishing
    // block, so the four modules can never publish under different versions.
    const val AISDK_VERSION = "0.1.0-SNAPSHOT"
}

/**
 * Version catalog access for precompiled script plugins. Deliberately NOT named `libs`: a top-level
 * `Project.libs` on the buildscript classpath shadows the generated `libs` accessor in every module build
 * file. Gradle does not generate a catalog accessor for precompiled script plugins at all
 * (gradle/gradle#15383), so convention plugins read the same catalog through this handle.
 */
val Project.aideLibs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * The Android namespace / Kotlin package a module owns, derived from its Gradle path so the two can never
 * drift: `:core:designsystem` → `com.sabreware.aide.core.designsystem`. This is the "match package path to
 * module path" guardrail (ARCHITECTURE.md §6) enforced by the build rather than by review.
 */
val Project.aideNamespace: String
    get() = (AideBuild.PACKAGE + path.replace(':', '.')).trimEnd('.')
