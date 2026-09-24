import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

// The :aisdk family's convention: a KMP library whose PUBLIC SURFACE is part of the deliverable.
//
// On top of `aide.kmp.library` it adds the three things a publishable SDK module needs and an app
// module does not: `explicitApi()` (a public surface should be a compiler-checked fact, not a
// hand-written convention), ABI validation (the committed dump under `api/` turns every surface
// change into a reviewable diff — `aisdkApiCheck` runs in `aideCheck`), and Dokka (the KDoc exists
// for consumers, so something has to render it — `make docs`).
//
// Kotlin's BUILT-IN ABI validation rather than the standalone binary-compatibility-validator
// plugin: the standalone plugin cannot even apply against AGP 9's KMP android target (duplicate
// `bcv-rt-jvm-cp` configuration), where the built-in one ships with the Kotlin Gradle plugin the
// build already runs on.
//
// Applied per-module rather than root-with-an-ignore-list because the ignore-list inverts the
// default: a new app module would become API-checked unless someone remembered to exclude it.
plugins {
    id("aide.kmp.library")
    id("org.jetbrains.dokka")
    `maven-publish`
}

// Publishing coordinates. KMP wires the per-target publications (sources jars included) on its own;
// what it cannot invent is the group, the version, and an artifactId better than the bare project
// name — ":aisdk:util" would publish as "util", a name that collides with half of Maven Central.
// The path-derived id ("aisdk", "aisdk-util", "aisdk-providers", "aisdk-runtime") keeps the four
// artifacts recognizably one family. `publishToMavenLocal` is the supported destination today; a
// remote repository block is a release-process decision, not a build-convention one.
group = "com.sabreware.aide"
version = AideBuild.AISDK_VERSION

private val aisdkArtifactBase = project.path.removePrefix(":").replace(':', '-')

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId = artifactId.replace(project.name, aisdkArtifactBase)
        pom {
            name.set(aisdkArtifactBase)
            description.set("A Kotlin Multiplatform port of the Vercel AI SDK's provider layer.")
            licenses {
                license {
                    name.set("Apache-2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                }
            }
        }
    }
}

kotlin {
    explicitApi()
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        enabled.set(true)
    }
}

// A module that ships a `Module.md` gets it rendered as the module front page in the Dokka output.
dokka {
    dokkaSourceSets.configureEach {
        val moduleDoc = project.layout.projectDirectory.file("Module.md").asFile
        if (moduleDoc.exists()) {
            includes.from(moduleDoc)
        }
    }
}
