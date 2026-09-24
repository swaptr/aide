/**
 * A KMP library that also carries Compose Multiplatform UI: the `compose { }` DSL + the multiplatform
 * Compose artifacts, plus the Compose compiler. Applied on top of [aide.kmp.library].
 *
 * The Compose artifacts are `api` on purpose — a UI module's public composables expose Compose types, so
 * every consumer needs them on its compile classpath (this is what lets :app and :desktopApp drop their own
 * Compose dependency declarations).
 */
plugins {
    id("aide.kmp.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    // Predictive-back (BackHandler / PredictiveBackHandler) is @ExperimentalComposeUiApi and used across
    // several surfaces — opt in module-wide rather than at ~a dozen call sites.
    compilerOptions {
        optIn.add("androidx.compose.ui.ExperimentalComposeUiApi")
    }

    android {
        // CMP-9547: under the AGP-9 KMP-library plugin, composeResources are not packaged into the consuming
        // APK unless Android resources are enabled for the library. Without this a resource lookup throws
        // MissingResourceException at runtime (compile-clean — device-only to catch).
        androidResources.enable = true
    }

    sourceSets {
        commonMain.dependencies {
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.ui)
            api(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
        }
    }
}
