package com.swaptr.aide.domain.tools.fs

interface FileSystemBackend {
    val kind: RootBackendKind

    fun list(
        root: RegisteredRoot,
        relPath: String,
        recursive: Boolean,
        maxEntries: Int,
    ): List<FsEntry>

    fun find(
        root: RegisteredRoot,
        relPath: String,
        pattern: String,
        mimePrefix: String?,
        maxDepth: Int,
        maxHits: Int,
    ): List<FsEntry>

    fun info(root: RegisteredRoot, relPath: String): FsEntry?

    fun readText(root: RegisteredRoot, relPath: String, maxBytes: Int): ReadTextResult

    fun move(
        root: RegisteredRoot,
        fromRel: String,
        toRel: String,
        overwrite: Boolean,
    ): FsEntry

    fun copy(
        root: RegisteredRoot,
        fromRel: String,
        toRel: String,
        overwrite: Boolean,
    ): FsEntry

    fun mkdir(root: RegisteredRoot, relPath: String): FsEntry

    fun delete(root: RegisteredRoot, relPath: String, useTrash: Boolean): DeleteResult

    data class DeleteResult(val deleted: Boolean, val trashed: Boolean)
}
