package com.sabreware.aide.app.data.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.sabreware.aide.core.common.media.CameraCapture
import com.sabreware.aide.core.common.media.ImageAttachmentStore
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class ImageStore(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) : ImageAttachmentStore {

    override suspend fun importFromUri(uriString: String): String = withContext(ioDispatcher) {
        val dir = File(context.filesDir, "attachments").apply { mkdirs() }
        val out = File(dir, "${UUID.randomUUID()}.jpg")
        decodeAndCompress(Uri.parse(uriString), out)
        out.absolutePath
    }

    // Hand camera intent the final attachment path (not cacheDir) so a process death
    // mid-capture doesn't lose the JPEG along with our pending state.
    override val supportsCameraCapture: Boolean = true

    override fun newCameraCapture(): CameraCapture {
        val dir = File(context.filesDir, "attachments").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return CameraCapture(uriString = uri.toString(), path = file.absolutePath)
    }

    // Downscale Pixel's 12 MP camera frames before they hit chat history or vision encoder.
    override suspend fun compressInPlace(path: String) = withContext(ioDispatcher) {
        val file = File(path)
        if (!file.exists() || file.length() == 0L) {
            Log.w("ImageStore", "compressInPlace skipped: file missing or empty: $path")
            return@withContext
        }
        // Sibling temp + atomic rename: failed encode leaves original intact.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        Log.i("ImageStore", "compressInPlace start: $path size=${file.length()}")
        try {
            decodeAndCompress(Uri.fromFile(file), tmp)
            if (!tmp.renameTo(file)) {
                // Fallback: drop original, copy tmp over.
                file.delete()
                if (!tmp.renameTo(file)) error("rename failed: ${tmp.absolutePath} -> ${file.absolutePath}")
            }
            Log.i("ImageStore", "compressInPlace done: $path size=${file.length()}")
        } catch (t: Throwable) {
            Log.e("ImageStore", "compressInPlace failed for $path", t)
            tmp.delete()
            throw t
        }
    }

    override suspend fun delete(path: String) = withContext(ioDispatcher) {
        runCatching { File(path).delete() }
        Unit
    }

    private fun decodeAndCompress(source: Uri, dest: File) {
        // Two-pass decode (bounds first, then inSampleSize) avoids holding full bitmap.
        // Bounds pass returns null Bitmap by design — elvis on the stream, not lambda result.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = context.contentResolver.openInputStream(source)
            ?: error("Cannot open input for $source")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }

        val sample = computeSampleSize(bounds.outWidth, bounds.outHeight, MAX_LONG_EDGE)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decodeStream = context.contentResolver.openInputStream(source)
            ?: error("Cannot reopen input for $source")
        val raw = decodeStream.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Cannot decode $source")

        val rotated = applyExifRotation(source, raw)
        FileOutputStream(dest).use { fos ->
            rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
        }
        if (rotated !== raw) raw.recycle()
        rotated.recycle()
    }

    private fun applyExifRotation(source: Uri, bitmap: Bitmap): Bitmap {
        // EXIF orientation is lost on naïve decode; rotate now so the model sees an
        // upright image and the preview thumb doesn't render sideways.
        val orientation = runCatching {
            context.contentResolver.openInputStream(source)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun computeSampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        val longest = maxOf(width, height)
        while (longest / sample > target * 2) sample *= 2
        return sample
    }

    private companion object {
        const val MAX_LONG_EDGE = 1024
        const val JPEG_QUALITY = 85
    }
}
