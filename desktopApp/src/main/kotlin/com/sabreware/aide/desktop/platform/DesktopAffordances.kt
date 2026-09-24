package com.sabreware.aide.desktop.platform

import androidx.compose.runtime.Composable
import com.sabreware.aide.ui.platform.AudioClipPlayer
import com.sabreware.aide.ui.platform.AudioClipPlayerFactory
import com.sabreware.aide.ui.platform.FolderPicker
import com.sabreware.aide.ui.platform.ModelFilePicker
import com.sabreware.aide.ui.platform.PhotoPicker
import com.sabreware.aide.ui.platform.PlatformAffordances
import com.sabreware.aide.ui.platform.UrlOpener
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.nameWithoutExtension
import io.github.vinceglb.filekit.path
import java.awt.Desktop
import java.io.File
import java.net.URI
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent

/**
 * What the shared UI can reach on a desktop host.
 *
 * **Two of the seven are simply not here**, and that is the whole point of the type: there is no camera
 * pipeline and no contacts database on a desktop, so `cameraCapture` and `contactPicker` are left at their
 * `null` defaults and the UI never draws the Camera tile or launches a contact pick. They used to be
 * `actual fun … = { }` and `= { onResult(null) }` — declarations whose only content was the absence they
 * were modelling.
 *
 * The host differences *within* desktop (Linux vs macOS vs Windows) do not appear here at all: FileKit
 * resolves the native dialog per host, and `java.awt.Desktop` resolves the browser. Where a real difference
 * does exist — app data directories, the system secret vault — it is answered by a binding chosen at
 * startup, not by a second copy of this file.
 */
val desktopAffordances: PlatformAffordances = PlatformAffordances(
    photoPicker = DesktopPhotoPicker,
    modelFilePicker = DesktopModelFilePicker,
    folderPicker = DesktopFolderPicker,
    urlOpener = DesktopUrlOpener,
    audioClipPlayers = DesktopAudioClipPlayers,
)

/** Native image dialog. The desktop "uri" is a plain absolute path. */
private object DesktopPhotoPicker : PhotoPicker {
    @Composable
    override fun rememberLauncher(onPicked: (String?) -> Unit): () -> Unit {
        val launcher = rememberFilePickerLauncher(type = FileKitType.Image) { file ->
            onPicked(file?.path)
        }
        return { launcher.launch() }
    }
}

private object DesktopModelFilePicker : ModelFilePicker {
    @Composable
    override fun rememberLauncher(onPicked: (uri: String, name: String) -> Unit): () -> Unit {
        val launcher = rememberFilePickerLauncher(type = FileKitType.File()) { file ->
            if (file != null) onPicked(file.path, file.nameWithoutExtension)
        }
        return { launcher.launch() }
    }
}

/** The picked path IS the grant — desktop has no SAF, and `DesktopGrantedFolderResolver` expects a path. */
private object DesktopFolderPicker : FolderPicker {
    @Composable
    override fun rememberLauncher(onPicked: (String) -> Unit): () -> Unit {
        val launcher = rememberDirectoryPickerLauncher { directory ->
            if (directory != null) onPicked(directory.path)
        }
        return { launcher.launch() }
    }
}

private object DesktopUrlOpener : UrlOpener {
    @Composable
    override fun rememberLauncher(): (String) -> Unit = { url ->
        runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url)) }
    }
}

private object DesktopAudioClipPlayers : AudioClipPlayerFactory {
    override fun create(): AudioClipPlayer = DesktopAudioClipPlayer()
}

/**
 * `javax.sound.sampled` over the app's own WAV clips (the recorder's output format). Non-WAV audio — an mp3
 * picked as a file — fails [bind] quietly: the chip renders and play is a no-op, the same degrade the
 * Android side gets from an unsupported codec.
 */
private class DesktopAudioClipPlayer : AudioClipPlayer {
    private var clip: Clip? = null

    override fun bind(path: String, onPrepared: (durationMs: Int) -> Unit, onCompletion: () -> Unit) {
        release()
        runCatching {
            val stream = AudioSystem.getAudioInputStream(File(path))
            val c = AudioSystem.getClip()
            c.open(stream)
            c.addLineListener { event ->
                // STOP fires on pause too — completion is STOP at the final frame.
                if (event.type == LineEvent.Type.STOP && c.framePosition >= c.frameLength) {
                    c.framePosition = 0
                    onCompletion()
                }
            }
            clip = c
            onPrepared((c.microsecondLength / 1000L).toInt())
        }
    }

    override fun play() {
        // Two statements, not one conditional: the rewind is conditional, the start is not.
        clip?.let { c ->
            if (c.framePosition >= c.frameLength) c.framePosition = 0
            c.start()
        }
    }

    override fun pause() {
        clip?.stop()
    }

    override fun positionMs(): Int = clip?.let { (it.microsecondPosition / 1000L).toInt() } ?: 0

    override fun release() {
        runCatching { clip?.close() }
        clip = null
    }
}
