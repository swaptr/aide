// :platform:android:surface:ime — the Aide keyboard: the InputMethodService, its View-based key/popup/transform machinery,
// the pages it hosts, the text-context repository and the Settings screen that enables and switches to it.
//
// A *surface*, like :platform:android:surface:assistant — its own Android component lifecycle over the same engine. It owns
// the View-layer resources it renders with (the aide_* color mirror, key dimensions, the keyboard drawables
// and its XML theme), so :app's resources no longer describe a keyboard it merely hosts.
plugins {
    id("aide.android.compose")
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            api(project(":core:domain"))
            api(project(":core:common"))
            api(project(":core:designsystem"))
            // The keyboard runs saved-prompt tasks and opens the tasks screens.
            implementation(project(":feature:tasks"))
            // SensitiveFieldPolicy (EditorInfo classification) + the launcher-intent helper.
            implementation(project(":platform:android"))
            implementation(libs.koin.core)
            implementation(libs.koin.android)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.jetbrains.lifecycle.viewmodel)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
            // The IME inflates Views (Chip, MaterialButton) and its theme parents off Material3.
            implementation(libs.google.material)
        }
    }
}
