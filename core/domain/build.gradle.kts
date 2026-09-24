// :core:domain — the whole `com.sabreware.aide.domain` spine: chat/model/llm/speech/tool value types, the
// repository + provider ports, and the use cases. Pure Kotlin: no Compose, no Android, no data-layer
// implementation. The layering rule that `LayeringRulesTest` used to assert (domain never imports data or
// ui, and carries no android.*) is now enforced by this module's dependency list — data and ui modules sit
// ABOVE it in the graph and cannot be referenced from here.
plugins {
    id("aide.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        commonMain.dependencies {
            api(project(":core:common"))
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
            api(libs.okio)
            implementation(libs.atomicfu)
        }
    }
}
