# R8 keep rules for the release build.
#
# This file was zero bytes while `isMinifyEnabled = false`, which meant the day someone flipped the flag the
# app would have shipped with none of the rules below — and every one of them protects something R8 cannot
# see, because it is reached by JNI, by a ServiceLoader, or by generated code that is only referenced from
# an annotation. Written and turned on together, on purpose: a keep-rules file that has never run is not a
# keep-rules file.
#
# Most third-party libraries here ship consumer rules (OkHttp, Ktor's OkHttp engine, Room runtime,
# WorkManager, Tink, Compose, Koin) and are deliberately NOT repeated. What follows is what nobody ships
# rules for: the native bridges, our own serialized types, and the reflective seams.

# ── Crash reports stay readable ──────────────────────────────────────────────────────────────────────────
# Without these a stack trace from a shrunk build names obfuscated methods with no line numbers, and the
# mapping file is the only way back.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# Generic signatures + annotations: kotlinx-serialization and Room both read them at runtime.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,RuntimeVisible*Annotations

# ── Sherpa-ONNX (JNI) ────────────────────────────────────────────────────────────────────────────────────
# The native library resolves these classes, their fields and their constructors BY NAME through JNI.
# Renaming any of them turns a working recogniser into an UnsatisfiedLinkError or a null field read.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class com.k2fsa.sherpa.onnx.** {
    native <methods>;
}

# ── LiteRT-LM + TFLite (JNI + Play Services delegate binding) ────────────────────────────────────────────
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.android.gms.tflite.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── kotlinx-serialization ────────────────────────────────────────────────────────────────────────────────
# The plugin generates a `Companion.serializer()` per @Serializable type and looks it up reflectively for
# sealed hierarchies and for @Serializable objects. This is the upstream-recommended rule set.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class **$* implements kotlinx.serialization.KSerializer {
    <fields>;
}
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
# Our own serialized types: chat wire models, preference blobs, and — critically — every navigation route.
# A route is looked up by its serial name, so an obfuscated route class navigates to nothing.
-keep,includedescriptorclasses @kotlinx.serialization.Serializable class com.sabreware.aide.** { *; }

# ── Room ─────────────────────────────────────────────────────────────────────────────────────────────────
# Generated `*_Impl` classes are instantiated by name from the @Database class, and entities are constructed
# reflectively by the generated cursor adapters.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class com.sabreware.aide.**.*_Impl { *; }
-keep @androidx.room.Entity class com.sabreware.aide.** { *; }
-keep class * implements androidx.room.RoomDatabaseConstructor { *; }
-dontwarn androidx.room.paging.**

# ── Ktor engines (ServiceLoader) ─────────────────────────────────────────────────────────────────────────
# Engines are discovered through META-INF/services; the factory object has no compile-time reference.
-keep class io.ktor.client.engine.okhttp.** { *; }
-keep class * implements io.ktor.client.engine.HttpClientEngineContainer { *; }
-keep class io.ktor.client.plugins.** { *; }

# ── Koin ─────────────────────────────────────────────────────────────────────────────────────────────────
# The graph is hand-written DSL, so nothing is scanned at runtime — but definitions are KEYED BY TYPE, and
# a ViewModel resolved from a composable is looked up by its class. Keeping the ViewModels keeps the keys.
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }

# ── Android components named in the manifest ─────────────────────────────────────────────────────────────
# AGP keeps manifest-declared classes automatically; these are the ones reached from OTHER apps' intents or
# from the system by class name, where a rename is a silent no-launch rather than a build error.
-keep class com.sabreware.aide.platform.android.surface.ime.AideInputMethodService { *; }
-keep class com.sabreware.aide.app.assistant.** { *; }
-keep class com.sabreware.aide.app.permission.RuntimePermissionActivity { *; }

# TFLite-support's generated value classes reference AutoValue's annotations, which are compile-time only
# and correctly absent from the runtime classpath.
-dontwarn com.google.auto.value.**

# ── Kotlin coroutines / atomicfu internals R8 warns about but nothing calls ───────────────────────────────
-dontwarn kotlinx.atomicfu.**
-dontwarn kotlinx.coroutines.debug.**
-dontwarn org.slf4j.**
