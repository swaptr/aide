import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * The baseline for every shared (non-application) module: a Kotlin Multiplatform library targeting Android
 * and the JVM desktop, built with AGP 9's dedicated `com.android.kotlin.multiplatform.library` plugin —
 * `com.android.library` is incompatible with the KMP plugin from AGP 9 on (ARCHITECTURE.md §1.1).
 *
 * Applying this plugin is what makes a new module cost one line instead of a hand-written build file.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    // One JVM toolchain for every module, so a build does not depend on which JDK happens to run Gradle
    // (17 on a laptop, 21 in CI) — that difference alone made builds non-hermetic across machines.
    jvmToolchain(AideBuild.JVM_TOOLCHAIN)

    // Compiler flags, declared ONCE for every module this plugin covers.
    //
    // `-Xannotation-default-target=param-property` used to be set on `:app` and `:server` alone — the two
    // modules that hold almost none of the annotated classes. The fifteen library modules that DO hold them
    // compiled with the old default, so constructor-annotation semantics differed across the graph
    // depending on which module a class happened to live in. A per-module opt-in to a language default is
    // exactly the kind of thing that belongs in the convention plugin.
    //
    // `-Xexpect-actual-classes` silences the "beta" warning for expect/actual CLASSES, which this codebase
    // uses deliberately for platform variation and which was otherwise warned about on every build.
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xannotation-default-target=param-property",
            "-Xexpect-actual-classes",
        )
    }

    android {
        namespace = aideNamespace
        compileSdk = AideBuild.COMPILE_SDK
        minSdk = AideBuild.MIN_SDK
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
        // Run commonTest on the Android host JVM as well as on desktop. Without this the AGP-9 KMP-library
        // plugin emits no android host-test compilation and only `desktopTest` would exercise commonTest.
        withHostTest { }
    }

    // Named "desktop" (not the default "jvm") to keep the target name meaningful across the tree; a plain
    // Kotlin/JVM consumer such as :desktopApp or :server still resolves it as the sole JVM-type variant.
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }

    sourceSets {
        // JVM-shared code: `src/jvmShared/kotlin`, auto-wired into BOTH JVM source sets when the directory
        // exists. Some implementations need the JVM (java.io, a JVM-only artifact) yet are identical on
        // Android and desktop; commonMain cannot hold them, and duplicating them into androidMain and
        // desktopMain is how ~700 lines of near-identical code accumulated once already.
        //
        // It is ONE source directory handed to two source sets, deliberately NOT an intermediate
        // `dependsOn` set: Kotlin does not officially support a JVM+Android shared source set, so each
        // target compiles the same files with its own stdlib and its own platform artifacts. There is no
        // metadata compilation to go wrong and one copy on disk. Stated here, once, so no module has to
        // re-derive it (:data:speech:sherpa was the first to need it).
        val jvmShared = layout.projectDirectory.dir("src/jvmShared/kotlin")
        if (jvmShared.asFile.isDirectory) {
            androidMain { kotlin.srcDir(jvmShared) }
            getByName("desktopMain") { kotlin.srcDir(jvmShared) }
        }

        commonMain.dependencies {
            implementation(aideLibs.findLibrary("kotlinx-coroutines-core").get())
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(aideLibs.findLibrary("kotlinx-coroutines-test").get())
        }
    }
}
