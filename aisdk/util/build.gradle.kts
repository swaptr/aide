// :aisdk:util — the shared wire plumbing every provider needs: HTTP, SSE framing, JSON, retry, ids.
//
// A Kotlin port of the reference's `provider-utils` package. The Ktor engine is INJECTED, never
// constructed here: a library that builds its own transport cannot share a connection pool with its host,
// and on Android that means a second thread pool nobody asked for.
//
// Nothing browser- or Node-specific is ported. `safe-node-fetch` and the reference's WebSocket helpers
// exist to re-implement what Ktor already provides; see aisdk/DESIGN.md for the list and for the two that
// were initially misfiled there and turned out to be SSRF defences.
plugins {
    id("aide.aisdk.library")
    alias(libs.plugins.kotlin.serialization)
}
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":aisdk"))
            api(libs.ktor.client.core)
            // Live speech is a WebSocket protocol at every vendor that offers one, with no HTTP-chunked
            // equivalent to fall back on, so the streaming transcription and translation contracts are
            // unimplementable without it. `api` because a provider names the session type.
            api(libs.ktor.client.websockets)
            implementation(libs.kotlinx.serialization.json)
            // okio for SHA-256 and HMAC-SHA256 in commonMain. This is what makes AWS SigV4 portable: the
            // alternative is java.security, which portabilityCheck rejects and Kotlin/Native does not have.
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
    }
}
