package com.swaptr.aide.domain.tools.fs

import android.util.Log
import com.swaptr.aide.domain.llm.AideTool
import com.swaptr.aide.domain.llm.Surface
import com.swaptr.aide.domain.llm.gates.WriteConfirmGate
import com.swaptr.aide.domain.tools.boolProp
import com.swaptr.aide.domain.tools.intProp
import com.swaptr.aide.domain.tools.objectSchema
import com.swaptr.aide.domain.tools.results.FileSystemResult
import com.swaptr.aide.domain.tools.stringProp
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

private const val TAG = "AideTools"
private const val DEFAULT_MAX_ENTRIES = 200
private const val DEFAULT_MAX_HITS = 100
private const val DEFAULT_MAX_DEPTH = 6
private const val READ_TEXT_HARD_CAP = 64 * 1024
private const val READ_TEXT_DEFAULT_CAP = 32 * 1024

private val FS_READ_SURFACES: Set<Surface> = setOf(Surface.CHAT, Surface.IME, Surface.VOICE)
private val FS_WRITE_SURFACES: Set<Surface> = setOf(Surface.CHAT)

private val FS_ERROR_CODES = setOf(
    FsErrorCode.UNKNOWN_ROOT,
    FsErrorCode.PATH_ESCAPE,
    FsErrorCode.NOT_FOUND,
    FsErrorCode.NOT_A_DIR,
    FsErrorCode.IS_A_DIR,
    FsErrorCode.BINARY_FILE,
    FsErrorCode.TOO_LARGE,
    FsErrorCode.EXISTS,
    FsErrorCode.IO_ERROR,
    FsErrorCode.BAD_ARGS,
    FsErrorCode.USER_CANCELLED,
    FsErrorCode.CONFIRM_TIMEOUT,
    FsErrorCode.CANCELLED_BY_USER,
    FsErrorCode.PERMISSION_DENIED,
    FsErrorCode.BACKEND_UNAVAILABLE,
)

class FileSystemToolset(
    private val roots: FileSystemRoots,
    private val confirmGate: WriteConfirmGate,
) {

    @Volatile
    var onToolStarted: (name: String, arg: String) -> Unit = { _, _ -> }

    fun asAideTools(): List<AideTool> = listOf(
        listFilesTool(),
        findFilesTool(),
        fileInfoTool(),
        readFileTool(),
        makeDirTool(),
        moveFileTool(),
        copyFileTool(),
        deleteFileTool(),
    )

    private fun listFilesTool(): AideTool = AideTool.Function(
        name = "ListFiles",
        description = "List entries in a directory. Use to explore the contents of a " +
            "granted folder before searching or operating. `rootKey` is one of the user's " +
            "registered roots (see system prompt). `relPath` is optional and defaults to " +
            "the root itself.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "rootKey" to stringProp("Registered root, e.g. 'Downloads'."),
            ),
            optionalProps = listOf(
                "relPath" to stringProp("POSIX-style path inside the root; empty for root."),
                "recursive" to boolProp("Recurse into subdirectories; default false."),
                "maxEntries" to intProp("Cap on returned entries; default 200."),
            ),
        ),
        handler = { args ->
            runTool("ListFiles", args) { rootKey, relPath ->
                val root = resolveRoot(rootKey)
                val recursive = args.boolOr("recursive", false)
                val maxEntries = args.intOr("maxEntries", DEFAULT_MAX_ENTRIES).coerceIn(1, 500)
                val backend = roots.backendFor(root)
                val entries = backend.list(root, relPath, recursive, maxEntries)
                FileSystemResult.Listing(
                    rootKey = rootKey,
                    relPath = relPath,
                    entries = entries.map { it.toJsonObject() },
                    truncated = entries.size >= maxEntries,
                )
            }
        },
        surfaces = FS_READ_SURFACES,
        errorCodes = FS_ERROR_CODES,
    )

    private fun findFilesTool(): AideTool = AideTool.Function(
        name = "FindFiles",
        description = "Search for files by glob pattern under a directory. Supports `*`, " +
            "`?`, and `[abc]`. Case-insensitive. Optional `mimePrefix` filters by mime " +
            "(e.g. 'application/pdf' or 'image/'). Returns up to 100 hits by default; " +
            "depth capped at 6.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "rootKey" to stringProp("Registered root, e.g. 'Downloads'."),
                "pattern" to stringProp("Glob pattern matched against file names, e.g. '*.pdf'."),
            ),
            optionalProps = listOf(
                "relPath" to stringProp("Starting subdirectory; empty for root."),
                "mimePrefix" to stringProp("Filter by mime prefix, e.g. 'image/'."),
                "maxHits" to intProp("Max hits; default 100."),
                "maxDepth" to intProp("Max recursion depth; default 6."),
            ),
        ),
        handler = { args ->
            runTool("FindFiles", args) { rootKey, relPath ->
                val root = resolveRoot(rootKey)
                val pattern = args.stringOr("pattern", "")
                if (pattern.isEmpty()) throw FsException(FsErrorCode.BAD_ARGS, "pattern required")
                val mimePrefix = args.optionalString("mimePrefix")
                val maxHits = args.intOr("maxHits", DEFAULT_MAX_HITS).coerceIn(1, 500)
                val maxDepth = args.intOr("maxDepth", DEFAULT_MAX_DEPTH).coerceIn(1, 12)
                val backend = roots.backendFor(root)
                val hits = backend.find(root, relPath, pattern, mimePrefix, maxDepth, maxHits)
                FileSystemResult.Found(
                    rootKey = rootKey,
                    relPath = relPath,
                    pattern = pattern,
                    entries = hits.map { it.toJsonObject() },
                    truncated = hits.size >= maxHits,
                )
            }
        },
        surfaces = FS_READ_SURFACES,
        errorCodes = FS_ERROR_CODES,
    )

    private fun fileInfoTool(): AideTool = AideTool.Function(
        name = "FileInfo",
        description = "Get metadata (size, mime, modified time, isDir) for a single path. " +
            "Use to verify a file exists before reading or moving it.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "rootKey" to stringProp("Registered root."),
                "relPath" to stringProp("Path inside the root."),
            ),
        ),
        handler = { args ->
            runTool("FileInfo", args) { rootKey, relPath ->
                val root = resolveRoot(rootKey)
                val entry = roots.backendFor(root).info(root, relPath)
                if (entry == null) FileSystemResult.Err(FsErrorCode.NOT_FOUND, "path not found")
                else FileSystemResult.Info(rootKey, entry.toJsonObject())
            }
        },
        surfaces = FS_READ_SURFACES,
        errorCodes = FS_ERROR_CODES,
    )

    private fun readFileTool(): AideTool = AideTool.Function(
        name = "ReadFile",
        description = "Read the text content of a file. Refuses binary files (PDFs, images, " +
            "etc.) and returns only metadata for those. Always capped to 32 KB; pass " +
            "`maxBytes` to lower the cap (hard ceiling 64 KB).",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "rootKey" to stringProp("Registered root."),
                "relPath" to stringProp("Path inside the root."),
            ),
            optionalProps = listOf(
                "maxBytes" to intProp("Read cap in bytes; default 32768, max 65536."),
            ),
        ),
        handler = { args ->
            runTool("ReadFile", args) { rootKey, relPath ->
                val root = resolveRoot(rootKey)
                val cap = args.intOr("maxBytes", READ_TEXT_DEFAULT_CAP)
                    .coerceIn(1, READ_TEXT_HARD_CAP)
                when (val result = roots.backendFor(root).readText(root, relPath, cap)) {
                    ReadTextResult.NotFound ->
                        FileSystemResult.Err(FsErrorCode.NOT_FOUND, "path not found")
                    is ReadTextResult.Binary -> FileSystemResult.BinaryRead(
                        FsErrorCode.BINARY_FILE,
                        "binary file (${result.reason})",
                        result.entry.toJsonObject(),
                    )
                    is ReadTextResult.Text -> FileSystemResult.TextRead(
                        result.entry.toJsonObject(),
                        result.text,
                        result.truncated,
                    )
                }
            }
        },
        surfaces = FS_READ_SURFACES,
        errorCodes = FS_ERROR_CODES,
    )

    private fun makeDirTool(): AideTool = AideTool.Function(
        name = "MakeDir",
        description = "Create a directory (and any missing parents) inside a registered root. " +
            "DESTRUCTIVE: the user will be prompted to allow this operation.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "rootKey" to stringProp("Registered root."),
                "relPath" to stringProp("Directory path to create."),
            ),
        ),
        handler = { args ->
            runTool("MakeDir", args) { rootKey, relPath ->
                val root = resolveRoot(rootKey)
                confirm(
                    toolName = "MakeDir",
                    summary = "Create folder '${rootKey}/${relPath}'?",
                    severity = WriteConfirmGate.Severity.WARN,
                    details = listOf(
                        WriteConfirmGate.KeyValue("Root", rootKey),
                        WriteConfirmGate.KeyValue("Path", relPath),
                    ),
                )?.let { return@runTool it }
                val entry = roots.backendFor(root).mkdir(root, relPath)
                FileSystemResult.DirCreated(rootKey, entry.toJsonObject())
            }
        },
        surfaces = FS_WRITE_SURFACES,
        errorCodes = FS_ERROR_CODES,
    )

    private fun moveFileTool(): AideTool = AideTool.Function(
        name = "MoveFile",
        description = "Move (rename) a file from one path to another inside the same root. " +
            "Set `overwrite=true` to replace an existing file at the destination. " +
            "DESTRUCTIVE: the user will be prompted to allow this operation.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "rootKey" to stringProp("Registered root."),
                "fromRel" to stringProp("Existing path."),
                "toRel" to stringProp("Destination path inside the same root."),
            ),
            optionalProps = listOf(
                "overwrite" to boolProp("Replace destination if it exists; default false."),
            ),
        ),
        handler = { args ->
            runTool("MoveFile", args, srcKey = "fromRel") { rootKey, fromRel ->
                val toRel = args.stringOr("toRel", "")
                if (toRel.isEmpty()) throw FsException(FsErrorCode.BAD_ARGS, "toRel required")
                PathSandbox.segments(toRel)
                val overwrite = args.boolOr("overwrite", false)
                val root = resolveRoot(rootKey)
                confirm(
                    toolName = "MoveFile",
                    summary = "Move '${rootKey}/${fromRel}' → '${rootKey}/${toRel}'" +
                        if (overwrite) " (overwrite existing)?" else "?",
                    severity = WriteConfirmGate.Severity.DANGER,
                    details = buildList {
                        add(WriteConfirmGate.KeyValue("From", "$rootKey/$fromRel"))
                        add(WriteConfirmGate.KeyValue("To", "$rootKey/$toRel"))
                        if (overwrite) add(WriteConfirmGate.KeyValue("Overwrite", "yes"))
                    },
                )?.let { return@runTool it }
                val entry = roots.backendFor(root).move(root, fromRel, toRel, overwrite)
                FileSystemResult.Moved(rootKey, entry.toJsonObject())
            }
        },
        surfaces = FS_WRITE_SURFACES,
        errorCodes = FS_ERROR_CODES,
    )

    private fun copyFileTool(): AideTool = AideTool.Function(
        name = "CopyFile",
        description = "Copy a file from one path to another inside the same root. " +
            "Set `overwrite=true` to replace an existing file at the destination. " +
            "DESTRUCTIVE: the user will be prompted to allow this operation.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "rootKey" to stringProp("Registered root."),
                "fromRel" to stringProp("Existing file path."),
                "toRel" to stringProp("Destination path inside the same root."),
            ),
            optionalProps = listOf(
                "overwrite" to boolProp("Replace destination if it exists; default false."),
            ),
        ),
        handler = { args ->
            runTool("CopyFile", args, srcKey = "fromRel") { rootKey, fromRel ->
                val toRel = args.stringOr("toRel", "")
                if (toRel.isEmpty()) throw FsException(FsErrorCode.BAD_ARGS, "toRel required")
                PathSandbox.segments(toRel)
                val overwrite = args.boolOr("overwrite", false)
                val root = resolveRoot(rootKey)
                confirm(
                    toolName = "CopyFile",
                    summary = "Copy '${rootKey}/${fromRel}' → '${rootKey}/${toRel}'" +
                        if (overwrite) " (overwrite existing)?" else "?",
                    severity = WriteConfirmGate.Severity.DANGER,
                    details = buildList {
                        add(WriteConfirmGate.KeyValue("From", "$rootKey/$fromRel"))
                        add(WriteConfirmGate.KeyValue("To", "$rootKey/$toRel"))
                        if (overwrite) add(WriteConfirmGate.KeyValue("Overwrite", "yes"))
                    },
                )?.let { return@runTool it }
                val entry = roots.backendFor(root).copy(root, fromRel, toRel, overwrite)
                FileSystemResult.Copied(rootKey, entry.toJsonObject())
            }
        },
        surfaces = FS_WRITE_SURFACES,
        errorCodes = FS_ERROR_CODES,
    )

    private fun deleteFileTool(): AideTool = AideTool.Function(
        name = "DeleteFile",
        description = "Delete a file. On the SAF backend this is permanent (Android's SAF " +
            "has no trash); on the dev Direct backend it is also permanent. " +
            "DESTRUCTIVE: the user will be prompted to allow this operation.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "rootKey" to stringProp("Registered root."),
                "relPath" to stringProp("Path inside the root."),
            ),
            optionalProps = listOf(
                "useTrash" to boolProp("Request soft-delete if backend supports it; default true."),
            ),
        ),
        handler = { args ->
            runTool("DeleteFile", args) { rootKey, relPath ->
                val root = resolveRoot(rootKey)
                val useTrash = args.boolOr("useTrash", true)
                confirm(
                    toolName = "DeleteFile",
                    summary = "Delete '${rootKey}/${relPath}'?",
                    severity = WriteConfirmGate.Severity.DANGER,
                    details = listOf(
                        WriteConfirmGate.KeyValue("Path", "$rootKey/$relPath"),
                        WriteConfirmGate.KeyValue("Trash", if (useTrash) "yes" else "no"),
                    ),
                )?.let { return@runTool it }
                val result = roots.backendFor(root).delete(root, relPath, useTrash)
                FileSystemResult.Deleted(rootKey, relPath, result.deleted, result.trashed)
            }
        },
        surfaces = FS_WRITE_SURFACES,
        errorCodes = FS_ERROR_CODES,
    )

    private inline fun runTool(
        toolName: String,
        args: JsonObject,
        srcKey: String = "relPath",
        body: (rootKey: String, primaryPath: String) -> FileSystemResult,
    ): JsonObject {
        return try {
            val rootKey = args.stringOr("rootKey", "")
            if (rootKey.isEmpty()) {
                return FileSystemResult.Err(FsErrorCode.BAD_ARGS, "rootKey required").toEnvelope()
            }
            val primary = PathSandbox.normalize(args.optionalString(srcKey))
            val summary = if (primary.isBlank()) rootKey else "$rootKey/$primary"
            Log.i(TAG, "$toolName called: $summary")
            onToolStarted(toolName, summary)
            body(rootKey, primary).toEnvelope()
        } catch (e: FsException) {
            FileSystemResult.Err(e.errorCode, e.message ?: e.errorCode).toEnvelope()
        } catch (e: SecurityException) {
            FileSystemResult.Err(FsErrorCode.PERMISSION_DENIED, e.message ?: "denied").toEnvelope()
        } catch (t: Throwable) {
            Log.w(TAG, "$toolName failed", t)
            FileSystemResult.Err(FsErrorCode.IO_ERROR, t.message ?: t::class.java.simpleName).toEnvelope()
        }
    }

    private fun resolveRoot(key: String): RegisteredRoot =
        roots.find(key) ?: throw FsException(FsErrorCode.UNKNOWN_ROOT, "unknown rootKey '$key'")

    // null = Approved (caller continues); non-null err = caller short-circuits.
    private fun confirm(
        toolName: String,
        summary: String,
        severity: WriteConfirmGate.Severity,
        details: List<WriteConfirmGate.KeyValue>,
    ): FileSystemResult? {
        val opId = UUID.randomUUID().toString()
        val outcome = confirmGate.await(
            WriteConfirmGate.Prompt(opId, toolName, summary, details, severity),
        )
        return when (outcome) {
            WriteConfirmGate.Result.Approved -> null
            is WriteConfirmGate.Result.Denied -> when (outcome.reason) {
                WriteConfirmGate.Result.Reason.USER_REJECTED ->
                    FileSystemResult.Err(FsErrorCode.USER_CANCELLED, "user rejected")
                WriteConfirmGate.Result.Reason.TIMEOUT ->
                    FileSystemResult.Err(FsErrorCode.CONFIRM_TIMEOUT, "confirm prompt timed out")
                WriteConfirmGate.Result.Reason.CANCELLED_BY_STOP ->
                    FileSystemResult.Err(FsErrorCode.CANCELLED_BY_USER, "session cancelled")
            }
        }
    }
}

private fun JsonObject.stringOr(key: String, default: String): String =
    this[key]?.jsonPrimitive?.contentOrNull() ?: default

private fun JsonObject.optionalString(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull()

private fun JsonObject.boolOr(key: String, default: Boolean): Boolean {
    val prim = this[key]?.jsonPrimitive ?: return default
    return prim.booleanOrNull ?: prim.contentOrNull()?.toBooleanStrictOrNull() ?: default
}

private fun JsonObject.intOr(key: String, default: Int): Int {
    val prim = this[key]?.jsonPrimitive ?: return default
    return prim.intOrNull ?: prim.contentOrNull()?.toIntOrNull() ?: default
}

private fun JsonPrimitive.contentOrNull(): String? =
    if (this is JsonNull) null else content
