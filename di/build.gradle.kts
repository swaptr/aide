// :di — the Koin graph shared by every application: `commonModules` (repositories, registries, providers,
// use cases, ViewModels) plus the deferred-bootstrap list. Each entry point appends its platform module and
// its feature set.
//
// Wiring is the one place that is allowed to see both the implementation layer and the UI, so it lives above
// both instead of inside either. That is what lets :ui drop its dependency on :data entirely — the ui→data
// rule a unit test used to ratchet is now a property of this graph.
plugins {
    id("aide.kmp.compose")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            api(project(":core:domain"))
            api(project(":core:designsystem"))
            // Every slice: :di is the module that binds implementations to ports, so it is the one place
            // that legitimately sees all of them. Nothing else does.
            api(project(":data"))
            api(project(":data:connector"))
            api(project(":data:llm"))
            // The portable AI SDK: :di constructs the providers, which is the one place that knows which
            // vendors this application can reach.
            implementation(project(":aisdk"))
            implementation(project(":aisdk:providers"))
            api(project(":data:speech"))
            api(project(":data:tools"))
            api(project(":ui"))
            implementation(libs.koin.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.okio)
            // The one HttpClient type the provider factories take: :di builds every `:aisdk` provider over
            // the injected streaming client.
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            // The contribution mechanism (`bind` + `getAll`) is exercised against a real container, so the
            // test source set needs the Koin runtime the modules are written against.
            implementation(libs.koin.core)
        }
    }
}
