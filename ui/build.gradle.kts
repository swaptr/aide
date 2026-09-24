// :ui — the shared UI: the feature screens and ViewModels, the app navigation graph and its Route tree. It
// depends on the design system and on the domain PORTS, and — deliberately — not on :data: the wiring that
// knows about implementations lives in :di, above both.
plugins {
    id("aide.kmp.compose")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // `api` throughout: this module's public surface (ViewModels, the shared screens) speaks these
            // modules' types, so every consumer compiles against them anyway.
            api(project(":core:common"))
            api(project(":core:domain"))
            api(project(":core:designsystem"))

            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            // FileKit — ONE native file dialog for every target (SAF / UIDocumentPicker / NSOpenPanel /
            // XDG portal). A portable library, not a platform fork: the generic "attach a file" picker in
            // ChatComposer is commonMain because of it. The pickers that are NOT here (photo, model file,
            // folder) stay host-provided affordances because their results feed host-shaped consumers —
            // Android's folder grant is a SAF tree uri its resolver parses a document id out of.
            implementation(libs.filekit.dialogs.compose)
            implementation(libs.koin.core)
            // JetBrains CMP navigation (the app graph is hosted from :app / :desktopApp), Coil 3 (KMP image
            // loader whose Ktor fetcher reuses our injected engine) and the KMP lifecycle/Koin-compose
            // bindings the shared ViewModels are built on. `api` because :app builds on all of them.
            api(libs.jetbrains.navigation.compose)
            api(libs.coil3.compose)
            api(libs.coil3.svg)
            api(libs.coil3.network.ktor)
            api(libs.jetbrains.lifecycle.viewmodel)
            api(libs.jetbrains.lifecycle.viewmodel.compose)
            api(libs.jetbrains.lifecycle.runtime.compose)
            api(libs.koin.compose)
            api(libs.koin.compose.viewmodel)
            // AboutLibraries core (KMP) — parses the OSS license JSON in the shared licenses screen.
            implementation(libs.aboutlibraries.core)
        }
        // :ui had no shared test source set at all — its ViewModels are the code both applications render
        // and nothing verified them. The shared fakes come from :core:domain's commonTest, as elsewhere.
        commonTest.get().kotlin.srcDir(rootProject.file(AideBuild.SHARED_FAKES))
        commonTest.dependencies {
            implementation(project(":core:domain"))
        }
    }
}

// Compose Multiplatform resources live in :core:designsystem (the module that owns the look) and keep the
// pinned `com.sabreware.aide.core.designsystem.resources` package, so every `Res.drawable.*` call site here
// is unchanged.
//
// kotlinx-datetime is pinned to 0.6.2 PROJECT-WIDE in the root build.gradle.kts (`allprojects`
// resolutionStrategy force) — our `Clock.System`/`kotlinx.datetime.Instant` call sites compile against
// 0.6.2, which 0.7.x moved to kotlin.time + dropped. See the root file for the full rationale.
