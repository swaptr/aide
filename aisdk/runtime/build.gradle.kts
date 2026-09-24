// :aisdk:runtime — the multi-round tool loop over any LanguageModel.
//
// The reference's `ai` package minus its UI-framework bindings: no React/Vue/Svelte/Angular adapters, no
// RSC, no UI message stream. Those exist to bridge a provider stream into a specific view layer, and a
// Compose consumer needs none of them.
plugins {
    id("aide.aisdk.library")
    // For the one runtime type that is persisted — `TextBatchReference`, the handle a batch outlives its
    // process by — and so a consumer's @Serializable class can be handed to generateObject(), which is the
    // whole point of taking a DeserializationStrategy.
    alias(libs.plugins.kotlin.serialization)
}
kotlin { sourceSets {
    commonMain.dependencies {
        api(project(":aisdk"))
        // `api`, not implementation: RetryPolicy and PollPolicy appear in this module's own public
            // signatures, so a consumer that cannot see :aisdk:util cannot name the defaults it overrides.
            api(project(":aisdk:util"))
        implementation(libs.kotlinx.serialization.json)
        // okio for HMAC-SHA256 in commonMain — the approval signature that binds a persisted "yes"
        // to the exact call it authorized. Same portability reason :aisdk:util uses it for SigV4.
        implementation(libs.okio)
    }
    commonTest.dependencies {
        // Test-only, and it points DOWN (runtime rank 35 -> providers rank 25). The end-to-end test
        // drives a REAL provider through the REAL loop, which is the only place the full signature
        // chain — wire capture, assembly, replay — is exercised together.
        implementation(project(":aisdk:providers"))
        implementation(project(":aisdk:util"))
        implementation(libs.ktor.client.mock)
        implementation(libs.ktor.client.core)
        implementation(libs.kotlinx.serialization.json)
    }
} }
