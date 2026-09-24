// :platform:android — Android platform machinery shared by the app AND by its surfaces (IME, assistant):
// the runtime-permission gate, the intent-broker relay, and the launcher-intent helper a service uses to
// open the app at a deep-link destination.
//
// It exists because a surface is a module of its own: without it, :platform:android:surface:assistant would have to depend on
// :app to reach `AndroidRuntimePermissionGate` or name `MainActivity`, i.e. point back at the top of the
// graph. Everything here is an implementation of a port declared in :core:domain.
//
// Deliberately NOT a Compose module. The one thing here that drew — the permission trampoline's dialogs —
// lives in :platform:android:ui, because every consumer of this machinery (:data:android,
// :data:speech:android, :platform:android:tools) was otherwise inheriting the design system to reach a permission
// gate. The gate opens the trampoline through the `PermissionTrampoline` port instead of naming its class.
plugins {
    id("aide.android.library")
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            api(project(":core:domain"))
            implementation(libs.androidx.core.ktx)
            implementation(libs.koin.core)
        }
    }
}
