package com.sabreware.aide.app.data.model

import com.sabreware.aide.core.domain.model.ModelImporter
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.sabreware.aide.core.domain.model.ImportedModelEntry
import com.sabreware.aide.core.domain.model.ModelImportConfig
import com.sabreware.aide.core.domain.model.ModelImportRepository
import com.sabreware.aide.core.domain.model.ImportedModelsStore
import com.sabreware.aide.core.domain.model.models
import com.sabreware.aide.core.domain.model.importedModelId
import com.sabreware.aide.core.domain.model.toSpec
import com.sabreware.aide.core.domain.util.AideLog
import com.sabreware.aide.core.domain.model.ModelStorage
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Persists imported-model entries (the `imported_models` document) and copies the picked file into the normal
 * [ModelStorage] layout so the resulting spec behaves like any on-disk local model. Mirrors gallery's
 * `ModelImportDialog.importModel` copy + `ModelManagerViewModel.saveImportedModels` bookkeeping.
 */
class ModelImportRepositoryImpl(
    private val context: Context,
    private val storage: ModelStorage,
    private val imported: ImportedModelsStore,
    private val ioDispatcher: CoroutineDispatcher,
) : ModelImportRepository, ModelImporter {

    override fun observe(): Flow<List<ImportedModelEntry>> = imported.models

    override suspend fun import(
        sourceUri: String,
        config: ModelImportConfig,
        onProgress: (Float) -> Unit,
    ): Result<ImportedModelEntry> = withContext(ioDispatcher) {
        runCatching {
            val uri = Uri.parse(sourceUri)
            val (pickedName, size) = readUriMeta(uri)
            val entry = ImportedModelEntry(
                id = importedModelId(config.displayName),
                displayName = config.displayName,
                fileName = sanitizeFileName(pickedName),
                sizeBytes = size.takeIf { it > 0 },
                topK = config.topK,
                topP = config.topP,
                temperature = config.temperature,
                maxTokens = config.maxTokens,
                visionIn = config.visionIn,
                audioIn = config.audioIn,
                thinking = config.thinking,
                preferGpu = config.preferGpu,
            )
            copyUriToFile(uri, storage.importTarget(entry.toSpec()).toFile(), size, onProgress)
            // A failed write means the model is not in the list — report it rather than claim success, and
            // take the copied weights (possibly gigabytes) back off the disk: nothing would ever point at them.
            if (imported.update { it.upsert(entry) } == null) {
                runCatching { storage.deleteFile(entry.toSpec()) }
                error("Couldn't save the imported model")
            }
            entry
        }.onFailure { AideLog.w(TAG, "model import failed", it) }
    }

    override suspend fun remove(id: String) {
        // One atomic read-modify-write; the entry it removed (if any) names the file to delete.
        var removed: ImportedModelEntry? = null
        imported.update { list ->
            removed = list.models.firstOrNull { it.id == id }
            list.without(id)
        } ?: return
        removed?.let { entry -> runCatching { storage.deleteFile(entry.toSpec()) } }
    }

    private fun readUriMeta(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "model.litertlm"
        var size = 0L
        runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
                            ?.let { name = cursor.getString(it) ?: name }
                        cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }
                            ?.let { size = cursor.getLong(it) }
                    }
                }
        }
        return name to size
    }

    private fun copyUriToFile(uri: Uri, target: File, size: Long, onProgress: (Float) -> Unit) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Can't open the selected file")
        try {
            target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                var lastTick = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    val now = System.currentTimeMillis()
                    if (size > 0 && now - lastTick > 200) { // throttle, like gallery
                        lastTick = now
                        onProgress((copied.toFloat() / size).coerceIn(0f, 1f))
                    }
                }
            }
            onProgress(1f)
        } catch (t: Throwable) {
            target.delete()
            throw t
        } finally {
            input.close()
        }
    }

    private fun sanitizeFileName(name: String): String =
        name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]+"), "_").ifBlank { "model.litertlm" }

    private companion object {
        private const val TAG = "ModelImport"
    }
}
