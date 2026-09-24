package com.sabreware.aide.app.platform

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.sabreware.aide.core.domain.tools.phone.ContactPickGate
import androidx.browser.customtabs.CustomTabsIntent
import com.sabreware.aide.core.domain.util.AideLog
import com.sabreware.aide.ui.platform.AudioClipPlayer
import com.sabreware.aide.ui.platform.AudioClipPlayerFactory
import com.sabreware.aide.ui.platform.CameraCapture
import com.sabreware.aide.ui.platform.ContactPicker
import com.sabreware.aide.ui.platform.FolderPicker
import com.sabreware.aide.ui.platform.ModelFilePicker
import com.sabreware.aide.ui.platform.PhotoPicker
import com.sabreware.aide.ui.platform.PlatformAffordances
import com.sabreware.aide.ui.platform.UrlOpener

/**
 * Everything the shared UI can reach on Android. Android happens to have all seven, which is why this file
 * lists all seven — a target that lacks one simply leaves it out of the constructor call and the UI stops
 * drawing its affordance.
 *
 * These moved here from `:ui`'s `androidMain` source set. They are Android-integration code, not shared UI:
 * every one of them is `rememberLauncherForActivityResult` over an `ActivityResultContract`, which is the
 * definition of the app module's job.
 */
val androidAffordances: PlatformAffordances = PlatformAffordances(
    photoPicker = AndroidPhotoPicker,
    cameraCapture = AndroidCameraCapture,
    contactPicker = AndroidContactPicker,
    modelFilePicker = AndroidModelFilePicker,
    folderPicker = AndroidFolderPicker,
    urlOpener = AndroidUrlOpener,
    audioClipPlayers = AndroidAudioClipPlayers,
)

/** PickVisualMedia — no runtime `READ_MEDIA_IMAGES` needed at minSdk 35. */
private object AndroidPhotoPicker : PhotoPicker {
    @Composable
    override fun rememberLauncher(onPicked: (String?) -> Unit): () -> Unit {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia(),
        ) { uri -> onPicked(uri?.toString()) }
        return {
            launcher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
    }
}

private object AndroidCameraCapture : CameraCapture {
    @Composable
    override fun rememberLauncher(onResult: (Boolean) -> Unit): (String) -> Unit {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicture(),
        ) { success -> onResult(success) }
        return { uriString -> launcher.launch(uriString.toUri()) }
    }
}

private object AndroidContactPicker : ContactPicker {
    @Composable
    override fun rememberLauncher(
        onResult: (ContactPickGate.ContactPickResult?) -> Unit,
    ): () -> Unit {
        val context = LocalContext.current
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val data = result.data?.data
            if (result.resultCode != Activity.RESULT_OK || data == null) {
                onResult(null)
                return@rememberLauncherForActivityResult
            }
            // ACTION_PICK grants temporary URI permission so querying doesn't need READ_CONTACTS.
            val picked = runCatching {
                context.contentResolver.query(
                    data,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val number = cursor.getString(0).orEmpty()
                        val name = cursor.getString(1)
                        if (number.isBlank()) {
                            null
                        } else {
                            ContactPickGate.ContactPickResult(displayName = name, number = number)
                        }
                    } else {
                        null
                    }
                }
            }.getOrNull()
            onResult(picked)
        }
        return {
            val intent = Intent(
                Intent.ACTION_PICK,
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            )
            runCatching { launcher.launch(intent) }.onFailure { onResult(null) }
        }
    }
}

/** SAF `OpenDocument`. The uri is opaque to the caller and consumed by the importer in the same session. */
private object AndroidModelFilePicker : ModelFilePicker {
    @Composable
    override fun rememberLauncher(onPicked: (uri: String, name: String) -> Unit): () -> Unit {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                val name = (uri.lastPathSegment ?: "Imported model")
                    .substringAfterLast('/')
                    .substringBeforeLast('.')
                onPicked(uri.toString(), name)
            }
        }
        return { launcher.launch(arrayOf("*/*")) }
    }
}

/**
 * SAF `OpenDocumentTree`. The tree uri is a CHOOSER result, not the access path: reads go through
 * `AndroidGrantedFolderResolver`, which turns the tree's document id into an absolute path the filesystem
 * toolset opens directly. That is why no persistable permission is taken here.
 */
private object AndroidFolderPicker : FolderPicker {
    @Composable
    override fun rememberLauncher(onPicked: (String) -> Unit): () -> Unit {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri -> if (uri != null) onPicked(uri.toString()) }
        return { launcher.launch(null) }
    }
}

/** Chrome Custom Tab — the OAuth sandbox. Never a WebView. */
private object AndroidUrlOpener : UrlOpener {
    @Composable
    override fun rememberLauncher(): (String) -> Unit {
        val context = LocalContext.current
        return { url ->
            AideLog.i("ConnOAuth", "openUrl url=$url")
            runCatching {
                CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, url.toUri())
            }.onFailure { AideLog.w("ConnOAuth", "CustomTab launch failed", it) }
        }
    }
}

private object AndroidAudioClipPlayers : AudioClipPlayerFactory {
    override fun create(): AudioClipPlayer = AndroidAudioClipPlayer()
}

/** Seeks back to 0 on completion so the clip is immediately replayable. */
private class AndroidAudioClipPlayer : AudioClipPlayer {
    private val player = MediaPlayer()

    override fun bind(path: String, onPrepared: (durationMs: Int) -> Unit, onCompletion: () -> Unit) {
        runCatching {
            player.reset()
            player.setDataSource(path)
            player.setOnPreparedListener { onPrepared(it.duration) }
            player.setOnCompletionListener {
                onCompletion()
                runCatching { player.seekTo(0) }
            }
            player.prepareAsync()
        }
    }

    override fun play() {
        runCatching { player.start() }
    }

    override fun pause() {
        runCatching { player.pause() }
    }

    override fun positionMs(): Int = runCatching { player.currentPosition }.getOrDefault(0)

    override fun release() {
        runCatching { player.release() }
    }
}
