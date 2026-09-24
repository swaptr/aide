package android.content.res

/**
 * Compile-time stub. The Sherpa-ONNX `com.k2fsa.sherpa.onnx.*` classes (shipped in the Android AAR's
 * `classes.jar`, reused verbatim on the JVM — see `desktopApp/build.gradle.kts`) name
 * `android.content.res.AssetManager` in their constructor descriptors: `Xxx(assetManager: AssetManager?,
 * config)`. Desktop has no Android runtime, so every engine passes `assetManager = null`, taking the
 * load-from-file JNI branch; this type is therefore never instantiated at runtime — it only has to resolve
 * at compile time so those constructor calls typecheck. (Verified: the classes load, init, and run real
 * inference on a plain JVM without any Android class present.)
 */
class AssetManager
