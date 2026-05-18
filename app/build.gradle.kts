import com.android.build.api.variant.HasHostTestsBuilder
import com.android.build.api.variant.HostTestBuilder
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android.gradle)
}

// Opts into Kotlin 2.2 future default: ctor-param annotations also apply to backing property.
kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

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
    namespace = "com.swaptr.aide"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.swaptr.aide"
        minSdk = 31
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
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
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
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
}

// AGP 9 defaults `android.onlyEnableUnitTestForTheTestedBuildType=true`, so only
// `testDebugUnitTest` is generated. Re-enable the release host-test variant so
// CI's `:app:testReleaseUnitTest` (release build) keeps working.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variantBuilder ->
        (variantBuilder as HasHostTestsBuilder)
            .hostTests[HostTestBuilder.UNIT_TEST_TYPE]
            ?.enable = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.litert.lm)
    // Play Services TFLite GPU — required so litertlm's GPU delegate can bind at runtime.
    // Without these the GPU backend silently falls back / fails Engine.initialize on some devices.
    implementation(libs.tflite)
    implementation(libs.tflite.gpu)
    implementation(libs.tflite.support)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.markdown.renderer.android)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.code)
    // IME inflates Views, needs XML-side Material theme stack.
    implementation(libs.google.material)
    implementation(libs.jsoup)
    implementation(libs.coil.compose)
    implementation(libs.tink.android)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.exifinterface)
    implementation(libs.compose.unstyled)
    implementation(libs.exp4j)
    // Sherpa-ONNX AAR (gitignored) fetched by downloadSherpaAar before compile.
    implementation(fileTree("libs") { include("*.aar") })
    implementation(libs.commons.compress)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Fetches ~54 MB Sherpa-ONNX AAR on first build; binary is gitignored.
val sherpaOnnxVersion = "1.13.2"
val sherpaOnnxAar = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaOnnxVersion.aar")
val downloadSherpaAar = tasks.register("downloadSherpaAar") {
    val target = sherpaOnnxAar.asFile
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar"
    outputs.file(target)
    onlyIf { !target.exists() }
    doLast {
        target.parentFile.mkdirs()
        logger.lifecycle("Fetching Sherpa-ONNX AAR from $url")
        URI(url).toURL().openStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }
}

tasks.named("preBuild") { dependsOn(downloadSherpaAar) }
