package com.sabreware.aide.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.sabreware.aide.core.domain.tools.phone.ContactPickGate

/**
 * The ways the shared UI reaches something OUTSIDE the app: the host's pickers, its camera, its contacts,
 * its browser, its audio hardware.
 *
 * **Every member is nullable, and null means "this target cannot do that".** The screen that wants one
 * resolves it, and draws its affordance only if it is there — the same rule every other capability in the
 * app follows.
 *
 * ### Why this is not `expect`/`actual`
 *
 * These were seven `expect` declarations in four files. `expect` expresses *variation*: every target must
 * supply an `actual` before anything compiles, so a target with nothing to offer is compelled to write an
 * empty one. That is absence modelled as a declaration, and it is not theoretical — the desktop `actual`s
 * for the folder and model-file pickers were literally `= {}`, so "Add folder" was a button that did
 * nothing on a target whose filesystem toolset is fully wired.
 *
 * With five platforms over three Kotlin targets (android, jvm, native) that is twenty-one obligations, and
 * a new target cannot come up with a working subset and grow into the rest. Here it can: it provides what
 * it has, leaves the rest at their defaults, and the UI adjusts.
 *
 * Absence is deliberately not silent about being *unprovided* — the local has no default value, so an
 * application that forgets to provide it fails loudly at first use rather than quietly rendering an app
 * with no pickers.
 */
@Immutable
class PlatformAffordances(
    val photoPicker: PhotoPicker? = null,
    val cameraCapture: CameraCapture? = null,
    val contactPicker: ContactPicker? = null,
    val modelFilePicker: ModelFilePicker? = null,
    val folderPicker: FolderPicker? = null,
    val urlOpener: UrlOpener? = null,
    val audioClipPlayers: AudioClipPlayerFactory? = null,
)

/**
 * Provided once per application, at the root of its composition. Has no default: a missing provider is a
 * wiring bug, and a wiring bug that renders a working-looking app with every picker inert is the failure
 * this whole type exists to prevent.
 */
val LocalPlatformAffordances = staticCompositionLocalOf<PlatformAffordances> {
    error("No PlatformAffordances provided — wrap the app content in CompositionLocalProvider")
}

// --- The affordances themselves ------------------------------------------------------------------------
//
// Each is `@Composable`-shaped rather than a plain lambda because obtaining one is composition work on at
// least one target: Android's pickers are `rememberLauncherForActivityResult`, which must run in the
// composition that will consume the result.

/** Picks one image. Hands back an opaque host reference (a SAF uri, an absolute path) or null. */
interface PhotoPicker {
    @Composable fun rememberLauncher(onPicked: (String?) -> Unit): () -> Unit
}

/**
 * Captures a photo into a destination the caller prepared. The returned lambda takes that destination as an
 * opaque host reference and launches; `onResult(success)` fires when the camera returns.
 *
 * Absent wherever there is no camera pipeline. Note this is the *launcher*: whether the app can stage a
 * capture destination at all is a separate, already-existing gate (`ImageAttachmentStore
 * .supportsCameraCapture`), and both must hold.
 */
interface CameraCapture {
    @Composable fun rememberLauncher(onResult: (Boolean) -> Unit): (String) -> Unit
}

/** Picks a contact. Absent on hosts with no contacts database — which is every desktop. */
interface ContactPicker {
    @Composable fun rememberLauncher(
        onResult: (ContactPickGate.ContactPickResult?) -> Unit,
    ): () -> Unit
}

/**
 * Picks a local model file to import. [onPicked] gets the host reference and a suggested display name.
 *
 * Distinct from whether importing is possible at all (`ModelsViewModel.canImportModels`, backed by a
 * nullable `ModelImporter`): a host can have a file dialog and no importer.
 */
interface ModelFilePicker {
    @Composable fun rememberLauncher(onPicked: (uri: String, name: String) -> Unit): () -> Unit
}

/** Picks a directory to grant the filesystem toolset. The tree is an opaque host reference. */
interface FolderPicker {
    @Composable fun rememberLauncher(onPicked: (String) -> Unit): () -> Unit
}

/** Opens a url in the host's browser. Never a WebView — connector OAuth requires the real browser. */
interface UrlOpener {
    @Composable fun rememberLauncher(): (String) -> Unit
}

/**
 * Plays one recorded voice clip. Stateful and single-use, so it is created per chip rather than injected
 * as a singleton — hence the factory.
 */
interface AudioClipPlayerFactory {
    fun create(): AudioClipPlayer
}

/** A bound clip. [release] is called exactly once, when the chip leaves composition. */
interface AudioClipPlayer {
    fun bind(path: String, onPrepared: (durationMs: Int) -> Unit, onCompletion: () -> Unit)
    fun play()
    fun pause()
    fun positionMs(): Int
    fun release()
}
