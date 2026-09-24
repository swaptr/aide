import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject

// :data:speech:sherpa — the on-device Sherpa-ONNX STT / TTS / VAD engines, the family→files bundle resolver
// and the SpeechProvider that fronts them. Written ONCE for Android and desktop.
//
// These used to exist twice, ~840 near-identical lines apart (`:app/data/speech/sherpa` and
// `:desktopApp/desktop/speech/sherpa`), because the code needs the JVM (`java.io.File`) and the
// `com.k2fsa.sherpa.onnx` classes — neither reachable from a commonMain that must also compile for
// non-JVM targets. Kotlin does not officially support a JVM+Android shared source set, so instead of an
// intermediate `dependsOn` set this module keeps ONE source directory (`src/jvmShared`) and hands it to both
// target source sets. Each target then compiles it with its own JVM stdlib and its own Sherpa artifact — no
// metadata compilation to go wrong, one copy on disk. `aide.kmp.library` wires `src/jvmShared/kotlin` into
// both JVM source sets automatically whenever the directory exists, so this is the tree-wide pattern rather
// than one module's trick.
plugins {
    id("aide.kmp.library")
}

// The `com.k2fsa.sherpa.onnx` API classes, extracted from the k2-fsa Android AAR. The SAME classes.jar runs
// byte-identical on the desktop JVM, so both targets compile against it; at runtime Android gets them from
// the AAR :app packages (which also carries the JNI .so) and desktop gets the jar itself, propagated by the
// `api(...)` below.
val sherpaOnnxVersion = libs.versions.sherpaOnnx.get()
val sherpaAar = rootProject.layout.projectDirectory.file("third_party/sherpa-onnx-$sherpaOnnxVersion.aar")
val sherpaClassesJar = layout.projectDirectory.file("libs/sherpa-onnx-classes-$sherpaOnnxVersion.jar")

/**
 * A typed task rather than an ad-hoc `doLast { copy { … } }`: the script-object references that form
 * captures (`copy`, `zipTree`, `logger`) cannot be serialized, so the ad-hoc version was the one thing in
 * the build that the configuration cache could not store. Injected [ArchiveOperations] /
 * [FileSystemOperations] are the supported equivalents.
 */
abstract class ExtractSherpaClasses : DefaultTask() {
    @get:InputFile
    abstract val aar: RegularFileProperty

    @get:Input
    abstract val downloadUrl: Property<String>

    @get:Input
    abstract val expectedSha256: Property<String>

    @get:OutputFile
    abstract val classesJar: RegularFileProperty

    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun extract() {
        val aarFile = aar.get().asFile
        // Download-to-staging + verify, so an interrupted fetch cannot leave a truncated AAR that every
        // later build accepts. Same rule as :app's downloadSherpaAar — the two fetch the same artifact.
        if (!aarFile.isFile || sha256(aarFile) != expectedSha256.get()) {
            aarFile.parentFile.mkdirs()
            val staging = File(aarFile.parentFile, "${aarFile.name}.part")
            staging.delete()
            logger.lifecycle("Fetching Sherpa-ONNX AAR from ${downloadUrl.get()}")
            URI(downloadUrl.get()).toURL().openStream().use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            }
            val actual = sha256(staging)
            if (actual != expectedSha256.get()) {
                staging.delete()
                throw GradleException(
                    "Sherpa-ONNX AAR checksum mismatch\n  expected ${expectedSha256.get()}\n  actual   $actual",
                )
            }
            staging.copyTo(aarFile, overwrite = true)
            staging.delete()
        }
        val out = classesJar.get().asFile
        out.parentFile.mkdirs()
        files.copy {
            from(archives.zipTree(aarFile)) { include("classes.jar") }
            into(out.parentFile)
        }
        val staged = File(out.parentFile, "classes.jar")
        staged.copyTo(out, overwrite = true)
        staged.delete()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}

val prepareSherpaClasses = tasks.register<ExtractSherpaClasses>("prepareSherpaClasses") {
    description = "Extracts the com.k2fsa.sherpa.onnx classes from the Sherpa-ONNX AAR."
    aar.set(sherpaAar)
    downloadUrl.set(
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/" +
            "sherpa-onnx-$sherpaOnnxVersion.aar",
    )
    expectedSha256.set(libs.versions.sherpaOnnxSha256.get())
    classesJar.set(sherpaClassesJar)
}

kotlin {
    sourceSets {
        // `src/jvmShared/kotlin` is added to androidMain + desktopMain by `aide.kmp.library`; nothing to
        // declare here beyond each target's own dependencies.
        androidMain {
            dependencies {
                api(project(":core:domain"))
                api(project(":data:speech"))
                // `api`, same as desktop. It used to be `compileOnly`, on the theory that the classes ship
                // inside the AAR `:app` packages — but a local `.aar` handed to `implementation(files(…))`
                // contributes classes ONLY, not the `jni/` payload, so `:app` now unpacks the native
                // libraries itself and does not depend on the AAR at all. The classes have to arrive here.
                api(files(prepareSherpaClasses))
            }
        }
        desktopMain {
            dependencies {
                api(project(":core:domain"))
                api(project(":data:speech"))
                // `api`, so :desktopApp gets the classes on its runtime classpath transitively and needs no
                // Sherpa extraction task of its own — only the per-OS native libs, which it still fetches.
                api(files(prepareSherpaClasses))
            }
        }
    }
}
