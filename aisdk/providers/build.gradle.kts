// :aisdk:providers — every vendor, one package each.
//
// One module rather than one per vendor: the vendors share a great deal (roughly twenty of them are
// configuration over the OpenAI-compatible wire), and a Gradle module per vendor would be thirty build
// files to keep in step for a library that is always consumed whole by this repo.
plugins {
    id("aide.aisdk.library")
    alias(libs.plugins.kotlin.serialization)
}
kotlin { sourceSets {
    commonMain.dependencies {
        api(project(":aisdk"))
        api(project(":aisdk:util"))
        implementation(libs.kotlinx.serialization.json)
    }
    commonTest.dependencies {
        implementation(libs.ktor.client.mock)
        implementation(libs.ktor.client.core)
    }
} }
