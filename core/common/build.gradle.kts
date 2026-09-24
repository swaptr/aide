// :core:common — the platform-neutral primitives every other module is allowed to depend on: dispatchers,
// the typed preference layer (`PrefKey`/`PreferenceStore`), attachment classification, the speech dictation
// ports and `PlatformPaths`. No Compose, no Android, no feature code — this module is the bottom of the
// graph, so nothing here may reference domain, data, or ui.
plugins {
    id("aide.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // `api`, not implementation: these types appear in this module's public signatures
            // (PrefKey codecs are kotlinx-serialization based, PlatformPaths returns okio paths, the
            // DataStore Preferences keys are what PreferenceStore is defined over).
            api(libs.kotlinx.serialization.json)
            api(libs.okio)
            api(libs.androidx.datastore.preferences.core)
            // `di/Names.kt` exposes koin `named(...)` qualifiers that BOTH the common modules and each
            // platform module resolve against, so koin-core is part of this module's public surface.
            api(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(libs.okio.fakefilesystem)
        }
    }
}
