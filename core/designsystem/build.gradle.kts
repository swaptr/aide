// :core:designsystem — the one look and the one set of building blocks: `ui/theme` (colors, type, shapes,
// spacing) and `ui/common` (AppMenu, AppSheet, PageScaffold, StatePane, skeletons, notices, …), together with
// the Compose Multiplatform resources (drawables + fonts) they render.
//
// It sits directly above :core:common / :core:domain / :core:navigation and imports nothing else of ours —
// that is what makes it safe for every feature and both app entry points to depend on it. Nothing in here may
// reference a feature, a repository, or the data layer.
plugins {
    id("aide.kmp.compose")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // `api` throughout: these types show up in the design system's own public composable signatures,
            // so consumers compile against them anyway.
            api(project(":core:common"))
            api(project(":core:domain"))
            api(libs.kotlinx.datetime)
            // Material3 Adaptive: currentWindowAdaptiveInfo() / WindowSizeClass — the source behind
            // LocalWindowSizeClass, which drives the responsive shell and the Sheet↔Dialog switch.
            api(libs.compose.material3.adaptive)
            // Predictive-back (BackHandler / PredictiveBackHandler) ships in its own CMP artifact, not in
            // compose.ui — AppSheet and the nested sheet navigator both drive it.
            api(libs.jetbrains.compose.ui.backhandler)
            // The markdown renderer is part of the look (assistant replies), so it ships with the design
            // system rather than with each screen that renders a reply.
            api(libs.markdown.renderer)
            api(libs.markdown.renderer.m3)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.koin.compose)
            // The Navigator port and the Feature contract moved here from :core:navigation / :core:feature.
            // Both are Compose types (a CompositionLocal, a NavGraphBuilder receiver), so they could not
            // fold into :core:domain without dragging Compose into every data module. This module is the
            // shared Compose foundation, which is exactly where they belong.
            api(libs.jetbrains.navigation.compose)
            api(libs.jetbrains.navigation3.ui)
            api(libs.koin.core)
        }
        // Host UI tests for the layout contracts (scrolling, the sheet/dialog body) — desktop only, since it is the
        // target whose tests run on a plain JVM with a real Compose renderer.
        val desktopTest by getting {
            dependencies {
                implementation(libs.jetbrains.compose.ui.test)
                implementation(compose.desktop.currentOs)
            }
        }
        androidMain.dependencies {
            // Android actuals for the shims: LocalActivity / result launchers, WindowCompat insets.
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
        }
    }
}

// The generated `Res` class is `public` and pinned to one package so every consumer keeps the same import
// (`com.sabreware.aide.core.designsystem.resources.Res`) no matter which module the drawable/font is rendered from.
compose.resources {
    publicResClass = true
    packageOfResClass = "com.sabreware.aide.core.designsystem.resources"
}
