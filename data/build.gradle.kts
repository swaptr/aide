// :data — the shared implementation layer: the Room conversation store, the model registry and its remote
// catalog, image generation, web search, the Ktor client factory, the preference store and the download
// stack. One module, one package per domain.
//
// Three domains are siblings instead of packages, and each earns it by having a consumer that would
// otherwise compile this module's whole dependency set to reach a handful of symbols:
//   :data:speech     — the Sherpa and Android speech engines build on it
//   :data:llm        — the on-device LiteRT provider builds on it
//   :data:tools      — the Android device toolsets build on it
//   :data:connector  — the Android and desktop OAuth halves build on it
// Nothing else was split, because nothing else had a consumer to protect.
//
// It depends on :core:domain (whose ports it implements) and on nothing above it: no ui, no feature, no
// navigation. That direction used to be asserted by a unit test; the module graph now makes the wrong
// import impossible to write.
plugins {
    id("aide.kmp.library")
    alias(libs.plugins.kotlin.serialization)
    // KMP Room lives here (DAOs/entities/@Database); Room's KSP codegen runs per target.
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

kotlin {
    sourceSets {
        commonTest.get().kotlin.srcDir(rootProject.file(AideBuild.SHARED_FAKES))
        commonTest.dependencies {
            implementation(project(":core:domain"))
            // Real filesystem semantics (rename, exists, delete) with no real disk — the download engine's
            // .part → final rename is the behaviour under test, so a stub map of paths would not do.
            implementation(libs.okio.fakefilesystem)
            // MockEngine + the client plugins the tests configure directly. The main-source Ktor deps are
            // `implementation`, so the test source set declares what it uses itself.
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)
        }
        commonMain.dependencies {
            api(project(":core:common"))
            api(project(":core:domain"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.okio)
            implementation(libs.atomicfu)
            implementation(libs.koin.core)
            // Networking written once in commonMain: Ktor client (engine injected per platform).
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            // ksoup — multiplatform HTML parser for the DuckDuckGo scrape/fetch.
            implementation(libs.ksoup)
            // KMP Room: runtime + BundledSQLiteDriver so the shared DB compiles/runs on Android AND desktop.
            // `api` (not implementation): AideDatabase (: RoomDatabase) and the RoomDatabase.Builder the
            // platform builders return are part of this module's public surface.
            api(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
        }
        // `src/jvmShared` (auto-wired by aide.kmp.library into androidMain + desktopMain) holds the one
        // JVM-only resident left here: the attachment-bytes reader.
    }
}

// Room schemas exported to VCS (checked in), one file per version, written once. A schema change bumps
// `@Database(version)` and lets KSP write the next N.json; `schemaCheck` fails the build on a rewrite.
room { schemaDirectory("$projectDir/schemas") }

// The androidx.room Gradle plugin's schemaDirectory does not (yet) wire `room.schemaLocation` into the KSP
// tasks the AGP-9 `com.android.kotlin.multiplatform.library` target emits, so exportSchema=true would
// silently no-op. Pass it explicitly per KSP arg — Room's documented mechanism.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspDesktop", libs.androidx.room.compiler)
}

// Both KSP tasks export to the SAME directory and neither declares it as an output, so nothing tells Gradle
// they conflict. One project means Gradle never runs them concurrently today, but that is a property of the
// executor rather than of this build — say it explicitly.
tasks.matching { it.name == "kspKotlinDesktop" }.configureEach {
    mustRunAfter(tasks.matching { it.name == "kspAndroidMain" })
}
