import javax.inject.Inject

plugins {
    id("aide.android.application")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutlibraries)
}

// Room (DAOs/entities/@Database + schema export + KSP codegen) lives entirely in :data/commonMain; :app
// carries no Room plugin, no KSP, and no Room deps — it consumes the DB via :di (room-runtime is exposed
// there as `api`). See data/build.gradle.kts.

val releaseVersionCode = (project.findProperty("versionCode") as? String)?.toInt() ?: 1
val releaseVersionName = (project.findProperty("versionName") as? String) ?: "1.0"

val keystoreFileProp = project.findProperty("AIDE_KEYSTORE_FILE") as? String
val keystorePasswordProp = project.findProperty("AIDE_KEYSTORE_PASSWORD") as? String
val keyAliasProp = project.findProperty("AIDE_KEY_ALIAS") as? String
val keyPasswordProp = project.findProperty("AIDE_KEY_PASSWORD") as? String
val hasReleaseSigning = !keystoreFileProp.isNullOrBlank() &&
    !keystorePasswordProp.isNullOrBlank() &&
    !keyAliasProp.isNullOrBlank() &&
    !keyPasswordProp.isNullOrBlank()

android {
    // namespace / compileSdk / minSdk / targetSdk / Java level come from `aide.android.application`, which
    // reads them off AideBuild — the same source every library module's convention plugin reads. (minSdk 35 is
    // the project floor — see CLAUDE.md "Project stage": no SDK-version branches below it.)
    defaultConfig {
        applicationId = "com.sabreware.aide"
        versionCode = releaseVersionCode
        versionName = releaseVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreFileProp!!)
                storePassword = keystorePasswordProp
                keyAlias = keyAliasProp
                keyPassword = keyPasswordProp
            }
        }
    }

    buildTypes {
        debug {
            ndk {
                // The arm64-only filter used to sit in defaultConfig, so it applied to debug as well:
                // `make install` onto an x86_64 emulator produced an APK with no Sherpa and no LiteRT
                // natives at all — a runtime failure on first use rather than a build error. Debug carries
                // both ABIs that matter (a real device and the emulator) and stops there; the other two
                // would add ~40 MB apiece for hardware nothing here targets.
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
            isMinifyEnabled = false
        }
        release {
            ndk {
                // Release ships arm64 only: the native payload (Sherpa + LiteRT) dominates the APK, and
                // every device this targets at minSdk 35 is arm64.
                abiFilters += listOf("arm64-v8a")
            }
            // R8 on, with rules that exist. Both halves matter: the flag was off AND the keep-rules file
            // was zero bytes, so "turn on shrinking" was one line away from shipping a build with no rules
            // for Sherpa's JNI classes, LiteRT, the generated serializers or the Room implementations.
            // The release workflow also publishes mapping.txt, which only exists once this is true.
            isMinifyEnabled = true
            // Resource shrinking stays OFF: Compose Multiplatform's composeResources are read by name at
            // runtime, and the shrinker cannot see those references. Code shrinking is the win here.
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    // :app has no unit tests of its own any more — every test lives in the module that owns its subject.
}

// Folding the Android-only modules in brought their unit tests with them, so :app has a host test source
// set now — it used to have none by design. `aideTest` discovers test tasks rather than listing them, so
// these started running the moment the source set existed.
// The shared fakes come from :core:domain's own commonTest — AGP exports no test fixtures, so point the
// unit-test source set at that one directory. It is the ANDROID source set's `kotlin` extension (added by
// the Kotlin Android plugin); `java.srcDir` on the same object feeds javac only, and `kotlin.sourceSets`
// has no "test" entry in a non-KMP module.
android.sourceSets.getByName("test").kotlin.srcDir(rootProject.file(AideBuild.SHARED_FAKES))

dependencies {
    // kotlin-test-junit explicitly, not the bare `kotlin("test")`: :app runs on JUnit 4, and only the
    // junit-flavoured artifact supplies the `kotlin.test.Test` typealias (the bare one gives the assertion
    // functions but no annotation, which fails in exactly one file and looks like a missing import).
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:domain"))

    // :di brings the Koin graph and, transitively, the UI (:ui) and the implementation layer (:data).
    implementation(project(":di"))
    // Android platform machinery shared by the app and its surfaces (permission gate, launcher intents),
    // plus the one piece of it that draws — the permission trampoline, kept in its own module so the
    // machinery stays Compose-free.
    implementation(project(":platform:android"))
    // The Android implementations that used to live inside :app.
    implementation(project(":data"))
    implementation(project(":data:connector"))
    implementation(project(":data:llm"))
    implementation(project(":data:speech"))
    implementation(project(":data:tools"))
    // Android-only feature module: the IME's saved-prompt tasks (screens, domain and its own Room DB).
    // :desktopApp has no such line, which is exactly how the feature is gated.
    implementation(project(":feature:tasks"))
    // Android-only surface: the digital-assistant service pair + its voice overlay. It brings its own
    // manifest entries, so hosting it costs exactly this line.
    // Android-only surface: the Aide keyboard (IME service + its View layer + keyboard settings).
    implementation(project(":platform:android:surface:ime"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Compose runtime/foundation/material3/ui + CMP navigation + coil3 + markdown are surfaced transitively
    // by :ui (they are `api` there), so :app no longer declares the compose BOM or those deps.
    // :app keeps only the Android-integration compose bits that live in :app's own composables
    // (activity-compose setContent, koin-androidx-compose, lifecycle-viewmodel-compose).
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    // OkHttp is retained ONLY as the Ktor engine (pulled by ktor-client-okhttp); this direct dep pins the
    // engine version (4.12 — see catalog). No direct okhttp3.* API remains in app code (Ktor migration,
    // docs/ktor-migration-plan.md).
    implementation(libs.okhttp)
    // Ktor client over the OkHttp engine — HTTP/2 + per-token streaming preserved.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.litert.lm)
    // Play Services TFLite GPU — required so litertlm's GPU delegate can bind at runtime.
    // Without these the GPU backend silently falls back / fails Engine.initialize on some devices.
    implementation(libs.tflite)
    implementation(libs.tflite.gpu)
    implementation(libs.tflite.support)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    // KMP-portable replacements for java.* in domain (Phase 2 de-JVM): dates + hashing + locks/atomics.
    implementation(libs.kotlinx.datetime)
    implementation(libs.okio)
    implementation(libs.atomicfu)
    // Koin DI — KMP-ready, wired by hand-written DSL in :di. See ARCHITECTURE.md §17.
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    // -core only: parses R.raw.aboutlibraries (generated by the aboutlibraries plugin); UI is our own.
    // The shared licenses screen parses an injected JSON string (AboutLibrariesJson), read here from R.raw.
    implementation(libs.aboutlibraries.core)
    // IME inflates Views, needs XML-side Material theme stack.
    implementation(libs.google.material)
    // Coil 3 (surfaced by :ui as `api`) — :app's KoinModules builds the connector ImageLoader with
    // coil3.* types. Custom Tabs (OAuth connector login) stays an :app dep (android platform actual).
    implementation(libs.androidx.browser)
    implementation(libs.tink.android)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.exifinterface)
    // On-device speech engines, shared with :desktopApp. It exposes the `com.k2fsa.sherpa.onnx` classes
    // transitively (`api`), and the JNI `.so` beside them is packaged through `jniLibs` below.
    implementation(project(":data:speech:sherpa"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // The debug-only tooling artifacts (ui-tooling, ui-test-manifest) are versionless — the main compose
    // deps moved to :ui, so the BOM platform must be present on the debug config to pin their versions.
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// The ~54 MB Sherpa-ONNX AAR, gitignored and cached at the repo root (third_party/). It is fetched and
// checksummed by :data:speech:sherpa, which reads the same file to extract its classes.jar — ONE downloader,
// so there is one place that knows the URL and the expected hash.
val sherpaOnnxVersion = libs.versions.sherpaOnnx.get()
val sherpaOnnxAar = rootProject.layout.projectDirectory.file("third_party/sherpa-onnx-$sherpaOnnxVersion.aar")
/**
 * The AAR's `jni/` tree, unpacked so AGP packages the `.so` files.
 *
 * They were NOT in the APK. `implementation(files(<aar>))` hands AGP a *file*, and the transform behind that
 * extracts `classes.jar` only — so the `com.k2fsa.sherpa.onnx` classes dexed fine while
 * `libsherpa-onnx-jni.so` was never packaged, and every on-device speech path was one
 * `System.loadLibrary` away from `UnsatisfiedLinkError`. It compiled, it installed, and it could not work.
 * Extracting into a `jniLibs` source directory is explicit about what ships.
 */
/**
 * Unpacks the Sherpa AAR's `jni/` tree so AGP packages the `.so` files.
 *
 * The `.so` were NOT in the APK. `implementation(files(<aar>))` hands AGP a *file*, and the transform behind
 * that extracts `classes.jar` only — so the `com.k2fsa.sherpa.onnx` classes dexed fine while
 * `libsherpa-onnx-jni.so` was never packaged, and every on-device speech path was one `System.loadLibrary`
 * away from `UnsatisfiedLinkError`. It compiled, it installed, and it could not work.
 *
 * The AAR is an INPUT here, never an output: `:data:speech:sherpa:prepareSherpaClasses` is the one task that
 * fetches and checksums it. Two tasks declaring the same file as their output is a race, and Gradle says so.
 */
abstract class UnpackSherpaJniLibs : DefaultTask() {
    @get:InputFile
    abstract val aar: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun unpack() {
        files.sync {
            from(archives.zipTree(aar)) { include("jni/**") }
            into(outputDir)
            // `jni/<abi>/lib.so` -> `<abi>/lib.so`, which is the layout jniLibs expects.
            eachFile { path = path.removePrefix("jni/") }
            includeEmptyDirs = false
        }
    }
}

val unpackSherpaJni = tasks.register<UnpackSherpaJniLibs>("unpackSherpaJni") {
    // The sherpa module owns fetching + verifying the AAR; this waits for it rather than racing it.
    dependsOn(":data:speech:sherpa:prepareSherpaClasses")
    aar.set(sherpaOnnxAar)
    outputDir.set(layout.buildDirectory.dir("sherpa-jni"))
}

// Wired through the variant API rather than `sourceSets.jniLibs.srcDir`: AGP 9 rejects a Provider there,
// and this is what carries the task dependency so the libraries exist before packaging runs.
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(unpackSherpaJni, UnpackSherpaJniLibs::outputDir)
    }
}

