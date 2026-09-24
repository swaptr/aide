// :data:llm — the `:aisdk` seam: the chat session over the runtime's loop, the per-vendor provider classes
// (Anthropic, Gemini, every OpenAI-compatible endpoint), the catalog listing and the non-chat modality
// engines (image, speech, transcription, embedding, rerank, video), each a thin translation between an AIDE
// port and the SDK.
//
// Cloud implementations are commonMain by rule — they are Ktor plus an injected engine, so they cost
// nothing to support everywhere. The on-device provider (LiteRT) is a package inside `:app`, because it
// really is platform-bound.
plugins {
    id("aide.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}
kotlin { sourceSets {
    commonMain.dependencies {
        api(project(":core:common"))
        api(project(":core:domain"))
        // The Ktor factory, and the remote catalog the providers map their model lists through.
        implementation(project(":data"))
        // The cloud speech adapters (`cloud/CloudSttEngine`, `CloudTtsEngine`): pure PCM/VAD code over
        // the domain audio ports, which the vendor classes here wrap so a vendor that speaks is also a
        // rung in the speech ladder. Below this module by rank; a second vendor module reuses them.
        implementation(project(":data:speech"))
        implementation(libs.kotlinx.serialization.json)
        implementation(libs.kotlinx.datetime)
        implementation(libs.atomicfu)
        implementation(libs.ktor.client.core)
        implementation(libs.ktor.client.content.negotiation)
        implementation(libs.ktor.serialization.kotlinx.json)
        // The portable AI SDK: the spec, the providers and the shared wire plumbing. This is what
        // sits behind the RemoteChatCodec seam — see aisdk/DESIGN.md.
        api(project(":aisdk"))
        implementation(project(":aisdk:providers"))
        // The runtime wrappers the non-chat modalities go through: batching against a vendor's
        // per-request ceiling, a bounded fan-out, and the did-it-produce-anything check that every
        // hand-written call to a bare `doGenerate` leaves out.
        implementation(project(":aisdk:runtime"))
        implementation(project(":aisdk:util"))
    }
    commonTest.get().kotlin.srcDir(rootProject.file(AideBuild.SHARED_FAKES))
    commonTest.dependencies {
        implementation(project(":core:domain"))
        // The catalog cache is what a cold start seeds from, so its tests exercise real filesystem
        // semantics (write, overwrite, delete) with no real disk.
        implementation(libs.okio.fakefilesystem)
        implementation(libs.ktor.client.mock)
        implementation(libs.ktor.client.core)
        implementation(libs.ktor.client.content.negotiation)
        implementation(libs.ktor.serialization.kotlinx.json)
        implementation(libs.kotlinx.serialization.json)
    }
} }
