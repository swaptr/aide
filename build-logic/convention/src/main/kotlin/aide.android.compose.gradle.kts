/**
 * An Android-only module ([aide.android.library]) that carries Compose UI. Mirrors [aide.kmp.compose] for
 * the single-target case: same Compose Multiplatform artifacts, same compiler, same opt-ins, so a screen
 * reads identically whether it ships everywhere or on Android alone.
 */
plugins {
    id("aide.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    compilerOptions {
        optIn.add("androidx.compose.ui.ExperimentalComposeUiApi")
    }

    sourceSets {
        androidMain.dependencies {
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.ui)
            api(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
        }
    }
}
