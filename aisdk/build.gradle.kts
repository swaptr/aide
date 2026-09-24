// :aisdk — the portable language-model specification.
//
// A Kotlin port of the Vercel AI SDK's `@ai-sdk/provider` package (Apache-2.0): the provider-agnostic
// contract every provider implements, and nothing else. No HTTP, no Ktor, no AIDE types — the whole point
// is that this module compiles on every target Kotlin has and that a provider module is the only thing
// that ever knows a vendor exists.
//
// Depends on kotlinx-serialization for one reason: `JsonObject` is the currency of `providerMetadata`,
// the type that carries what the spec deliberately refuses to model (Anthropic's thinking `signature`,
// Gemini's `thoughtSignature`, cache control, …). See DESIGN.md in this directory.
plugins {
    id("aide.aisdk.library")
    alias(libs.plugins.kotlin.serialization)
}
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            // Flow is on the public surface (StreamResult.stream); a consumer must see coroutines to compile.
            api(libs.kotlinx.coroutines.core)
        }
    }
}

// Dokka aggregation: `:aisdk:dokkaGeneratePublicationHtml` renders the whole family's API reference
// (make docs). Deliberately NOT part of aideCheck — docs generation is slow and not a structural
// invariant.
dependencies {
    dokka(project(":aisdk:util"))
    dokka(project(":aisdk:providers"))
    dokka(project(":aisdk:runtime"))
}
