// :feature:tasks — the IME's saved-prompt tasks: the Task/TaskGroup domain, its OWN Room database, the
// task list/detail/edit screens and the `TasksFeature` that plugs them into the app.
//
// Android-only, because the feature exists to back the keyboard surface. Gating is the module's placement:
// :desktopApp never depends on it, so none of this — down to the Room tables — is compiled or packaged
// there. A feature owns its whole subtree, storage included (ARCHITECTURE.md: an Android-only feature gets
// its own Android-only DB rather than tables in the shared AideDatabase).
plugins {
    id("aide.android.compose")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            api(project(":core:domain"))
            api(project(":core:designsystem"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.koin.core)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.jetbrains.lifecycle.viewmodel)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            // Classic Android Room: a leaf source set cannot use the KMP @ConstructedBy form, so the DB is
            // built with Room.databaseBuilder(context, …). See data/task/TaskDatabase.kt.
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
        }
    }
}

// The tasks schema is exported next to the feature that owns it. Same explicit KSP arg as :data — the
// androidx.room plugin does not wire schemaDirectory into the KSP tasks the AGP-9 KMP-library plugin emits.
room { schemaDirectory("$projectDir/schemas") }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
}
