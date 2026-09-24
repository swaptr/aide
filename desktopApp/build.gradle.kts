import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.net.URI
import java.util.zip.ZipFile

// Desktop (JVM Compose Desktop) application — hosts the whole shared KMP core + Compose UI.
// Plain kotlin-jvm module (not KMP): `project(":ui")` resolves the sole jvm-type target (jvm("desktop"))
// unambiguously. jetbrains-compose supplies the `compose { }` / `compose.desktop { }` DSL; kotlin-compose is
// the compose-compiler. See ARCHITECTURE.md §17.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
    // The JVM flavour of the same plugin :app applies. No version: the root build already puts it on the
    // buildscript classpath (`apply false`), and re-requesting a version there is an error.
    id("com.mikepenz.aboutlibraries.plugin")
}

// Run + compile the desktop app on JDK 21 (LTS). Required: `multiplatform-markdown-renderer-m3`'s desktop
// artifact is JDK-21 bytecode (class file v65) — running on JDK 17 throws UnsupportedClassVersionError at
// composition. Compose Desktop targets a modern LTS anyway. The shared modules (jvmTarget 11) are consumed
// fine by a 21 runtime. Gradle auto-detects the installed Temurin 21 toolchain.
kotlin { jvmToolchain(21) }

// ----------------------------------------------------------------------------------------------------------
// Sherpa-ONNX on the JVM. The `com.k2fsa.sherpa.onnx.*` API classes (and the `android.content.res.AssetManager`
// compile stub their constructor descriptors name) come from :data:speech:sherpa, which owns the engines for
// both platforms. What stays here is the half that is genuinely desktop-only:
//   `sherpa-onnx-native-lib-<os>-v<version>.jar` — the per-OS native libs (`libsherpa-onnx-jni` +
//      `libonnxruntime`). `libsherpa-onnx-jni` is built with `RPATH=$ORIGIN`, so co-locating both in one dir
//      lets the JNI lib resolve onnxruntime without `LD_LIBRARY_PATH`. The classes self-load the JNI lib via
//      `System.loadLibrary("sherpa-onnx-jni")`, which needs that dir on `java.library.path` (set in the
//      `compose.desktop` block below). Verified end-to-end on JDK 21 (real Silero VAD inference).
val sherpaOnnxVersion = libs.versions.sherpaOnnx.get()
val sherpaNativesDir = layout.buildDirectory.dir("sherpaNatives")

// os.name/os.arch → the k2-fsa native-lib asset token.
val sherpaOsToken: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val a = if (arch.contains("aarch64") || arch.contains("arm64")) "aarch64" else "x64"
    when {
        os.contains("mac") || os.contains("darwin") -> "osx-$a"
        os.contains("win") -> "win-x64"
        else -> "linux-$a" // linux + anything else
    }
}

// Per-OS native libs (.so/.dylib/.dll) → one flat, co-located dir (for RPATH=$ORIGIN + java.library.path).
// A typed task (not an ad-hoc `doLast`): `copy {}`/`zipTree` inside a script-scoped action capture the
// script object, which the configuration cache refuses to serialize — injected ArchiveOperations and
// FileSystemOperations are the cache-safe forms of the same calls.
abstract class PrepareSherpaNativesTask : DefaultTask() {
    @get:Input abstract val sherpaVersion: Property<String>
    @get:Input abstract val osToken: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val archives: ArchiveOperations
    @get:Inject abstract val fs: FileSystemOperations

    @TaskAction
    fun prepare() {
        val outDir = outputDir.get().asFile
        val token = osToken.get()
        val version = sherpaVersion.get()
        val marker = File(outDir, ".ready-$version-$token")
        if (marker.exists()) return
        val jar = File(temporaryDir, "native-lib-$token.jar")
        // `if (!jar.exists())` on its own cached a TRUNCATED file forever: a dropped connection left a
        // partial jar that every later build accepted, and the checkout stayed broken until someone deleted
        // it by hand. Download to a staging file, verify it is a readable archive, and only then move it
        // into place — so a failed fetch leaves nothing behind and simply retries next time.
        //
        // (No pinned checksum here, unlike the Android AAR: this artifact is per OS and per architecture,
        // so it would be five hashes to keep current, and a stale one breaks the build for everyone on that
        // platform. Integrity of the archive is what actually prevents the failure being fixed.)
        if (!jar.isFile || !jar.isReadableZip()) {
            jar.delete()
            jar.parentFile.mkdirs()
            val staging = File(temporaryDir, "native-lib-$token.jar.part")
            staging.delete()
            val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$version/sherpa-onnx-native-lib-$token-v$version.jar"
            logger.lifecycle("Fetching Sherpa-ONNX native libs ($token) from $url")
            URI(url).toURL().openStream().use { i -> staging.outputStream().use { o -> i.copyTo(o) } }
            if (!staging.isReadableZip()) {
                staging.delete()
                throw GradleException("Sherpa-ONNX native libs downloaded from $url are not a readable archive")
            }
            staging.copyTo(jar, overwrite = true)
            staging.delete()
        }
        outDir.mkdirs()
        fs.copy {
            from(archives.zipTree(jar)) {
                include("sherpa-onnx/native/**")
                includeEmptyDirs = false
                eachFile { path = name } // flatten: all libs land co-located in outDir
            }
            into(outDir)
        }
        marker.createNewFile()
        logger.lifecycle("Unpacked Sherpa-ONNX native libs → ${outDir.absolutePath}")
    }

    /** True when [this] opens as a zip with at least one entry — i.e. a whole download, not a prefix. */
    private fun File.isReadableZip(): Boolean = runCatching {
        ZipFile(this).use { zip -> zip.entries().hasMoreElements() }
    }.getOrDefault(false)
}

val prepareSherpaNatives = tasks.register<PrepareSherpaNativesTask>("prepareSherpaNatives") {
    sherpaVersion.set(sherpaOnnxVersion)
    osToken.set(sherpaOsToken)
    outputDir.set(sherpaNativesDir)
}

dependencies {
    // :di brings the Koin graph and, transitively, the UI (:ui) and the implementation layer (:data).
    implementation(project(":di"))
    implementation(project(":data"))
    implementation(project(":data:connector"))
    implementation(project(":data:llm"))
    implementation(project(":data:speech"))
    implementation(project(":data:tools"))
    // FileKit — the native open-file / open-directory dialogs behind this app's PlatformAffordances. It is
    // `implementation` in :ui (which uses it for the generic attachment picker), so it is not transitive.
    implementation(libs.filekit.dialogs.compose)
    implementation(compose.desktop.currentOs)

    // DI + coroutines (swing = Dispatchers.Main on the desktop JVM).
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)

    // Platform impls the desktop DI module builds directly (:data/:ui keep these `implementation`, so they
    // are not exposed transitively — declare them here). Room-runtime, coil3 and the CMP artifacts ARE `api`
    // there, so they arrive transitively.
    implementation(libs.tink.jvm)               // SecureStore AEAD
    implementation(libs.ktor.client.okhttp)     // Ktor OkHttp engine
    implementation(libs.okhttp)
    implementation(libs.androidx.datastore.preferences.core) // USER_PREFS DataStore<Preferences>
    implementation(libs.okio)                   // okio.Path for createWithPath / disk paths
    implementation(libs.kotlinx.serialization.json) // connector cache JSON
    implementation(libs.coil3.network.ktor)     // connector-icon network fetcher (KtorNetworkFetcherFactory)

    // On-device speech: the engines + the `com.k2fsa.sherpa.onnx.*` classes come from the shared module
    // (which exposes the extracted classes.jar as `api`). The native `.so`/`.dylib`/`.dll` come from the
    // per-OS native-lib jar via `prepareSherpaNatives`, loaded through `-Djava.library.path` (see below).
    // (commons-compress, which unpacks the `.tar.bz2` bundles, arrives at runtime with :data's extractor.)
    implementation(project(":data:speech:sherpa"))

    // The Koin graph test resolves every definition in `desktopModules` — the one check that catches a
    // missing binding, which Koin's lazy resolution otherwise defers to whoever opens the screen.
    testImplementation(kotlin("test"))
}

tasks.withType<Test> { useJUnitPlatform() }

// The native libs must be unpacked before the app is launched or packaged (they're loaded via
// `-Djava.library.path` below). `run`/package task names are contributed by the compose plugin.
tasks.matching { it.name in setOf("run", "runDistributable", "createDistributable", "packageDistributionForCurrentOS", "prepareAppResources") }
    .configureEach { dependsOn(prepareSherpaNatives) }

compose.desktop {
    application {
        mainClass = "com.sabreware.aide.desktop.MainKt"
        // On-device speech: point the JVM native-lib search at the co-located Sherpa `.so`/`.dylib`/`.dll`
        // (populated by `prepareSherpaNatives`). The `com.k2fsa.sherpa.onnx.*` classes self-load
        // `sherpa-onnx-jni` via `System.loadLibrary`, which resolves names against `java.library.path`;
        // `libsherpa-onnx-jni`'s `RPATH=$ORIGIN` then finds `libonnxruntime` in the same dir. The abs build
        // path suits dev `run`; a packaged distribution would bundle the same dir via `appResourcesRootDir`
        // and repoint this at `$APPDIR`.
        jvmArgs += "-Djava.library.path=${sherpaNativesDir.get().asFile.absolutePath}"
        // The compose `run`/package tasks do NOT inherit the kotlin `jvmToolchain` launcher, so point javaHome
        // at the toolchain-resolved JDK 21 explicitly — else `run` launches on the gradle JVM (17) and throws
        // UnsupportedClassVersionError loading the JDK-21 module classes + markdown-m3 desktop artifact.
        javaHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
            .get().metadata.installationPath.asFile.absolutePath
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "Aide"
            packageVersion = "1.0.0"
        }
    }
}

// ----------------------------------------------------------------------------------------------------------
// OSS licences, for real.
//
// The licences screen used to be handed the literal `{"libraries":[],"licenses":{}}` on desktop — a screen
// that rendered nothing, which is absence dressed up as an implementation. The aboutlibraries plugin has a
// JVM flavour; applying it generates the same dependency/licence metadata `:app` gets from `R.raw`, and
// packaging it as a resource makes the shared screen show it.
val aboutLibrariesDir = layout.buildDirectory.dir("generated/aboutLibraries")
sourceSets.named("main") { resources.srcDir(aboutLibrariesDir) }
tasks.named<ProcessResources>("processResources") { dependsOn("exportLibraryDefinitions") }
