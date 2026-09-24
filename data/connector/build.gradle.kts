// :data:connector — connectors end to end: the catalogs and directories they are discovered through, the
// OAuth client that authorizes one, and the MCP client that then speaks to it.
//
// `connector` and `mcp` are one module and two packages because they are two layers of one feature, not two
// features: a Connector is the presented, discoverable thing; MCP is the protocol we reach it over. The
// module boundary is where the MCP SDK stops leaking — before this split, every consumer of `:data`
// compiled it.
plugins {
    id("aide.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}
kotlin { sourceSets {
    commonMain.dependencies {
        api(project(":core:common"))
        api(project(":core:domain"))
        // The Ktor factory only; the connector stack owns everything else it needs.
        implementation(project(":data"))
        implementation(libs.kotlinx.serialization.json)
        implementation(libs.kotlinx.datetime)
        implementation(libs.atomicfu)
        implementation(libs.okio)
        implementation(libs.ktor.client.core)
        implementation(libs.ktor.client.content.negotiation)
        implementation(libs.ktor.serialization.kotlinx.json)
        // MCP client — connects to external tool servers over StreamableHTTP, reusing the platform-injected
        // Ktor engine. The SDK bundles client + server transports; AIDE uses only the StreamableHTTP CLIENT,
        // so the Ktor SERVER stack it drags in (~1 MB, ktor-server-*) is dead weight — exclude it.
        // The KMP source-set `dependencies {}` handler has no `Provider` + configure-lambda overload, and
        // the unwrapped catalog `.get()` is an immutable minimal dependency that rejects `exclude`. So pass
        // the coordinate as a String notation — that overload materializes a fresh mutable
        // ExternalModuleDependency the exclude-lambda can configure.
        val mcpSdk = libs.mcp.kotlin.sdk.get()
        implementation("${mcpSdk.module}:${mcpSdk.versionConstraint.requiredVersion}") {
            exclude(group = "io.ktor", module = "ktor-server-core")
            exclude(group = "io.ktor", module = "ktor-server-sse")
            exclude(group = "io.ktor", module = "ktor-server-websockets")
            exclude(group = "io.ktor", module = "ktor-server-content-negotiation")
        }
    }
    commonTest.get().kotlin.srcDir(rootProject.file(AideBuild.SHARED_FAKES))
    commonTest.dependencies {
        implementation(project(":core:domain"))
        implementation(libs.ktor.client.mock)
        implementation(libs.ktor.client.core)
        implementation(libs.ktor.client.content.negotiation)
        implementation(libs.ktor.serialization.kotlinx.json)
        implementation(libs.kotlinx.serialization.json)
    }
} }
