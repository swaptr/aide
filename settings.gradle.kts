pluginManagement {
    // Convention plugins (`aide.kmp.library`, `aide.kmp.compose`, …) — every module's build file applies one
    // of these instead of repeating target/SDK/Compose setup. See ARCHITECTURE.md §5 step 1.
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Aide"

// --- The portable AI SDK: a Kotlin port of Vercel's `@ai-sdk/provider` (Apache-2.0) ---
// A standalone library that knows NOTHING about AIDE — no ProviderId, no ChatProvider, no Koin — which is
// what lets it target everything Kotlin does and lets AIDE be merely one of its consumers. The arrow is
// `:data:llm -> :aisdk`, never back. Reference sources are vendored at `third_party/`, which is NOT a
// Gradle subproject and is therefore invisible to every structural check. See TODO.md / aisdk/DESIGN.md.
include(":aisdk")
// The shared wire plumbing every provider needs. Its own module so a consumer that only wants the spec
// (to write a provider against, or to type a port) never pulls Ktor in.
include(":aisdk:util")
// Every vendor, one package each. See aisdk/DESIGN.md for why this is one module and not thirty.
include(":aisdk:providers")
// The multi-round tool loop. Depends on the spec, never on a provider — which is what lets one loop serve
// every vendor.
include(":aisdk:runtime")

// --- Foundation (no Compose, no Android framework) ---
include(":core:common")
include(":core:domain")
include(":core:designsystem")

// --- The Android platform: everything that exists BECAUSE the host OS is Android ---
// The machinery, the one piece of it that draws, the device toolsets, and the OS entry points (surfaces).
// A second platform would grow the same shape under its own root; the shared layers above are untouched by
// either. A module here disappears if the platform does — as opposed to `:data:android`, which is the
// Android HALF of a port that still exists elsewhere, and so stays in the data layer.
include(":platform:android")


// --- Implementation layer ---
include(":data")
// Domain slices. `:data` itself is the thin plumbing they share; each slice below is one domain, and a
// consumer that needs one no longer compiles Room and the MCP SDK to reach it.
include(":data:speech")
include(":data:tools")
include(":data:connector")
include(":data:llm")
include(":data:speech:sherpa")

// --- Features (a platform that omits one never compiles it) ---
include(":feature:tasks")

// --- Android surfaces: the ways the OS lets the user reach us from OUTSIDE the app ---
// Each owns its own UI, because its shape is dictated by the OS API it plugs into — the IME is an
// `InputMethodService` drawing Android Views, the assistant a `VoiceInteractionSession` drawing an overlay.
// Nothing about that is portable; what a future platform would share is the idea, not the code.
include(":platform:android:surface:ime")

// --- The Koin graph (sees both the implementation layer and the UI, so it sits above both) ---
include(":di")

// --- Application entry points + the shared UI they both host ---
include(":app")
include(":ui")
include(":desktopApp")
include(":server")
