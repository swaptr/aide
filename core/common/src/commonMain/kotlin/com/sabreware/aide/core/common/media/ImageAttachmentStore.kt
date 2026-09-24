package com.sabreware.aide.core.common.media

/**
 * Platform port for staging image attachments (gallery import + camera capture + downscale).
 *
 * commonMain interface — speaks portable `String`s (a URI string / file path), not android `Uri`/`File`.
 * The platform impl ([com.sabreware.aide.app.data.attachments.ImageStore] on Android) parses/produces the
 * platform types; feature code (ChatViewModel) and the impl both depend only on this port. Injected via Koin.
 */
interface ImageAttachmentStore {

    /** Decode + downscale + EXIF-rotate the picked image (given as a URI string); returns the stored file path. */
    suspend fun importFromUri(uriString: String): String

    /**
     * Whether this platform can capture from a camera at all.
     *
     * A capability, not a policy: desktop's [newCameraCapture] used to throw
     * `UnsupportedOperationException`, and nothing asked first — so the Attach sheet drew a Camera tile
     * there that could only fail. An affordance for something the platform cannot do should not be drawn.
     */
    val supportsCameraCapture: Boolean

    /**
     * Allocate a final attachment file + a (FileProvider) URI string for a camera intent to write into.
     * Only valid when [supportsCameraCapture]; callers gate on that rather than catching.
     */
    fun newCameraCapture(): CameraCapture

    /** Downscale a full-res capture in place (atomic). */
    suspend fun compressInPlace(path: String)

    /** Delete a staged attachment file. */
    suspend fun delete(path: String)
}

/** [uriString] = a content/file URI string for the camera intent; [path] = the backing file path. */
data class CameraCapture(val uriString: String, val path: String)
