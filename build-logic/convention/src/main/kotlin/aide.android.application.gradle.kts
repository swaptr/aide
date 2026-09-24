/**
 * The Android application entry point (`:app`). Its counterpart for libraries is [aide.android.library];
 * this plugin exists for the same reason — so the SDK levels, the JVM target and the namespace are read
 * from [AideBuild] instead of being hand-written next to every other module's convention-plugin copy, where
 * they can silently drift.
 *
 * Everything genuinely application-shaped (applicationId, signing, build types, packaging, ABI filters)
 * stays in `app/build.gradle.kts`: it is one module's policy, not a convention.
 */
plugins {
    id("com.android.application")
}

kotlin {
    // Same toolchain and the same language flags as every library module — see [aide.kmp.library]. The
    // flags used to be written out by hand in `app/build.gradle.kts` and in `:server`, and nowhere else.
    jvmToolchain(AideBuild.JVM_TOOLCHAIN)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xannotation-default-target=param-property",
            "-Xexpect-actual-classes",
        )
    }
}

android {
    // The application's own code lives in com.sabreware.aide.app — the same module-path-mirrors-package-path
    // rule every library module follows — so that is what R/BuildConfig and the manifest's relative component
    // names resolve against. `applicationId` (the install identity) is set by the module and stays
    // com.sabreware.aide.
    namespace = aideNamespace
    compileSdk { version = release(AideBuild.COMPILE_SDK) }

    defaultConfig {
        minSdk = AideBuild.MIN_SDK
        targetSdk = AideBuild.TARGET_SDK
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
