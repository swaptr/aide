import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * A module that exists **only on Android** — a device surface (IME, voice assistant) or a feature that has
 * no meaning elsewhere. Same KMP plugin as [aide.kmp.library], but with the Android target alone: the code
 * is neither compiled nor packaged for desktop, which is the whole gating mechanism. No `expect`/`actual`
 * pair, no stub, no `if (isAndroid)` — a platform that lacks the feature simply never depends on the module.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
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
        withHostTest { }
        // AGP's KMP library plugin defaults Android resource processing OFF, and with it off a module that
        // has a `src/androidMain/res/` tree gets no R class at all — consumers then fail with
        // "Unresolved reference 'R'" against a package whose resources plainly exist. `:platform:android`
        // is exactly that shape (a drawable and the trampoline theme, no Compose), and the break was
        // invisible for as long as a stale R.jar survived in the build directory: it reproduces on any
        // clean checkout. Enabled here rather than per module so a module that grows a `res/` directory
        // just works. It also covers CMP-9547 for `aide.android.compose`, which builds on this plugin:
        // composeResources are not packaged into the consuming APK unless the library has Android
        // resources enabled.
        androidResources.enable = true
    }

    sourceSets {
        androidMain.dependencies {
            implementation(aideLibs.findLibrary("kotlinx-coroutines-core").get())
        }
        // An Android-only module has no commonTest source set — its host tests live in `androidHostTest`.
        // Declared here so writing the FIRST test in one of these modules does not begin with discovering
        // that kotlin-test is not on the classpath. (`:platform:android:tools` had no tests at all, and this is why
        // the first one cost more than it should have.)
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test"))
            implementation(aideLibs.findLibrary("kotlinx-coroutines-test").get())
        }
    }
}
