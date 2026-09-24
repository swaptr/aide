import org.gradle.plugin.use.PluginDependency

// The convention plugins themselves. Written as precompiled script plugins (`src/main/kotlin/aide.*.gradle
// .kts`) so each one gets the type-safe DSL accessors of the plugins it applies — `kotlin { android { … } }`
// under AGP 9's KMP-library plugin has no stable programmatic accessor otherwise.
plugins {
    `kotlin-dsl`
}

group = "com.sabreware.aide.buildlogic"

kotlin {
    // Gradle 9 compiles the Kotlin DSL on the Gradle JVM (17 here); match it so the compiled plugin classes
    // load in the same daemon.
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// The plugin JARs a convention plugin needs on its COMPILE classpath only: at execution time they are
// already on the consuming build's buildscript classpath, because the root build.gradle.kts declares every
// one of them with `apply false`. Coordinates come from the catalog's [plugins] entries via the standard
// `<id>:<id>.gradle.plugin:<version>` marker artifacts, so versions stay single-sourced.
fun Provider<PluginDependency>.marker(): String =
    get().let { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }

dependencies {
    compileOnly(libs.plugins.kotlin.multiplatform.marker())
    compileOnly(libs.plugins.kotlin.jvm.marker())
    compileOnly(libs.plugins.android.application.marker())
    compileOnly(libs.plugins.android.kotlin.multiplatform.library.marker())
    compileOnly(libs.plugins.kotlin.compose.marker())
    compileOnly(libs.plugins.jetbrains.compose.marker())
    compileOnly(libs.plugins.kotlin.serialization.marker())
    compileOnly(libs.plugins.dokka.marker())
}
