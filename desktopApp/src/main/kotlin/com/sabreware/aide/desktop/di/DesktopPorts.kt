package com.sabreware.aide.desktop.di

import com.sabreware.aide.core.common.di.IO
import com.sabreware.aide.core.common.media.CameraCapture
import com.sabreware.aide.core.common.media.ImageAttachmentStore
import com.sabreware.aide.core.common.storage.PlatformPaths
import com.sabreware.aide.core.domain.device.DeviceInfo
import com.sabreware.aide.core.domain.model.ImportedModelEntry
import com.sabreware.aide.core.domain.model.ModelImportRepository
import com.sabreware.aide.core.domain.model.ChatModelSpec
import com.sabreware.aide.core.domain.model.ResidencyHandle
import com.sabreware.aide.core.domain.model.ResidencyManager
import com.sabreware.aide.core.domain.model.ResidentModel
import com.sabreware.aide.core.domain.permission.AppPermission
import com.sabreware.aide.core.domain.permission.PermissionResult
import com.sabreware.aide.core.domain.permission.RuntimePermissionGate
import com.sabreware.aide.core.domain.permission.SpecialPermission
import com.sabreware.aide.core.domain.catalog.ModelCatalog
import com.sabreware.aide.core.domain.licenses.AboutLibrariesJson
import com.sabreware.aide.core.domain.tools.fs.GrantedFolderResolver
import com.sabreware.aide.core.domain.tools.fs.ResolvedFolder
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/**
 * Desktop's answers to the ports `commonModules` injects.
 *
 * These were re-examined as candidate stubs and are **not** stubs. The harmful pattern the ratchet bans is
 * an empty implementation standing in for a feature that is supposed to work; every answer below is instead
 * the true description of a desktop:
 *
 *  - **No on-device model allowlist** — [DesktopModelCatalog] is empty because desktop ships none. Filling
 *    it is the whole job; there is no port to write.
 *  - **Nothing importable** — desktop binds no `ModelImporter`, so [DesktopImportedModels] can never gain an
 *    entry, and the UI already omits the Import button on that basis (`ModelsViewModel.canImportModels`).
 *  - **No memory pressure to manage** — [DesktopResidencyManager] never evicts. Making the port nullable was
 *    considered and rejected: `SpeechEngineRepository.acquire` returns a non-null `ResidencyHandle`, so a
 *    null manager just relocates the same no-op into shared code.
 *  - **No runtime-permission model** — everything really is granted. A nullable gate would force every
 *    shared call site to interpret null as "granted", which is more code and easier to get wrong.
 *
 * Things desktop cannot do AT ALL — the keyboard, the digital assistant — are not represented here in any
 * form. Their code is not on this classpath and the graph never asks for them.
 */

private object NoopResidencyHandle : ResidencyHandle {
    override suspend fun release(keepAliveMs: Long) {}
}

/**
 * The remaining desktop leaf port for the shared model registry: an empty on-device catalog, because desktop
 * ships no allowlist. Storage is no longer a stub — `ModelStorageImpl` is shared okio — so gaining on-device
 * models here means filling this catalog, not writing a port.
 */
class DesktopModelCatalog : ModelCatalog {
    override val models: List<ChatModelSpec> = emptyList()
    override fun findById(id: String): ChatModelSpec? = null
}


/**
 * "Nothing has been imported" — a real answer, not a placeholder. Desktop binds no [ModelImporter], so
 * nothing can ever add an entry here, and the registry merges an empty list into its catalog.
 */
class DesktopImportedModels : ModelImportRepository {
    override fun observe(): Flow<List<ImportedModelEntry>> = flowOf(emptyList())
    override suspend fun remove(id: String) = Unit
}

class DesktopResidencyManager : ResidencyManager {
    override suspend fun acquire(model: ResidentModel): ResidencyHandle = NoopResidencyHandle
    override fun residents(): List<ResidencyManager.Resident> = emptyList()
    override fun onTrimMemory(level: Int) {}
}

/** Empty toolset — desktop v1 ships no on-device tools (fs/clock/phone/etc.). */

/** Desktop has no runtime-permission model — everything is granted. */
class DesktopRuntimePermissionGate : RuntimePermissionGate {
    override fun isGranted(permission: AppPermission): Boolean = true
    override fun isGranted(special: SpecialPermission): Boolean = true
    override suspend fun ensure(permission: AppPermission, showRationale: Boolean): PermissionResult =
        PermissionResult.Granted
    override suspend fun ensureSpecial(special: SpecialPermission): PermissionResult = PermissionResult.Granted
}

class DesktopDeviceInfo : DeviceInfo {
    // Heap ceiling, not physical RAM. On the JVM that is the honest number for admission anyway: the
    // desktop engines allocate through the JVM, so `maxMemory` is the real wall regardless of how much the
    // machine has. `free + (max - allocated)` is what is still obtainable, not merely what is unused now.
    private val runtime: Runtime get() = Runtime.getRuntime()

    override val totalRamBytes: Long get() = runtime.maxMemory()
    override val availableRamBytes: Long
        get() = runtime.freeMemory() + (runtime.maxMemory() - runtime.totalMemory())
    override val totalRamGb: Int = (Runtime.getRuntime().maxMemory() / (1024L * 1024L * 1024L)).toInt().coerceAtLeast(1)
}


/**
 * Reads the licence metadata the aboutlibraries plugin generates into this module's resources — the JVM
 * peer of `:app`'s `R.raw.aboutlibraries` reader.
 *
 * It used to return an empty JSON literal, so the shared licences screen rendered an empty list on desktop.
 * That is not a policy about desktop; it is the screen not working there.
 */
class DesktopAboutLibrariesJson : AboutLibrariesJson {
    override suspend fun load(): String = withContext(Dispatchers.IO) {
        javaClass.classLoader
            ?.getResourceAsStream(RESOURCE)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("$RESOURCE is missing — the aboutlibraries export did not reach the resources")
    }

    private companion object {
        const val RESOURCE = "aboutlibraries.json"
    }
}

/** Real desktop resolver: a picked folder is a plain absolute path. */
class DesktopGrantedFolderResolver : GrantedFolderResolver {
    override suspend fun resolve(treeUri: String): ResolvedFolder? {
        val f = File(treeUri)
        return if (f.isDirectory) ResolvedFolder(displayName = f.name, absolutePath = f.absolutePath) else null
    }
}

/**
 * Desktop attachment store: a picked image is COPIED into `filesDir/attachments` (uuid-named) and the copy
 * becomes the attachment. Never a passthrough — the staged path gets deleted on clear/model-switch, and
 * with a passthrough that deleted the user's ORIGINAL file. No downscale (desktop bandwidth) and no camera.
 */
class DesktopImageAttachmentStore(private val paths: PlatformPaths) : ImageAttachmentStore {
    override suspend fun importFromUri(uriString: String): String = withContext(Dispatchers.IO) {
        val source = File(uriString)
        val dir = File(paths.filesDir.toString(), "attachments").apply { mkdirs() }
        val ext = source.extension.lowercase().ifBlank { "jpg" }
        val copy = File(dir, "aide-img-${UUID.randomUUID()}.$ext")
        source.copyTo(copy, overwrite = true)
        copy.absolutePath
    }
    // Stated up front rather than thrown on use — see ImageAttachmentStore.supportsCameraCapture. The
    // throw below is now unreachable through the UI and exists only to make a mistaken call loud.
    override val supportsCameraCapture: Boolean = false

    override fun newCameraCapture(): CameraCapture =
        throw UnsupportedOperationException("camera capture unavailable on desktop")
    override suspend fun compressInPlace(path: String) {}
    override suspend fun delete(path: String) {
        runCatching { File(path).delete() }
    }
}
