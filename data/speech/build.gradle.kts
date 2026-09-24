// :data:speech — the portable half of on-device speech: what bundles exist, where they land on disk, how
// they are installed, and the AssetSource that teaches the download stack about them. The ENGINES are
// separate modules below this one (`:data:speech:sherpa`, `:data:speech:android`) because each needs a
// different native stack; this is what they share.
//
// `SpeechAssetSource` lives here rather than beside the download engine on purpose: the ratchet says the
// registered source owns the URL and the on-disk layout, so it belongs with the asset it describes. Keeping
// it next to the scheduler is what made the generic download stack depend on one specific capability.
plugins {
    id("aide.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}
kotlin { sourceSets {
    commonMain.dependencies {
        api(project(":core:common"))
        api(project(":core:domain"))
        implementation(libs.kotlinx.serialization.json)
        implementation(libs.okio)
    }
    // src/jvmShared holds the commons-compress bundle extractor: JVM-only, identical on Android and desktop,
    // and the piece a future Kotlin/Native target has to write for itself.
    androidMain.dependencies { implementation(libs.commons.compress) }
    getByName("desktopMain").dependencies { implementation(libs.commons.compress) }
    getByName("desktopTest").dependencies { implementation(libs.commons.compress) }
    commonTest.get().kotlin.srcDir(rootProject.file(AideBuild.SHARED_FAKES))
    commonTest.dependencies {
        implementation(libs.okio.fakefilesystem)
        implementation(project(":core:domain"))
    }
} }
