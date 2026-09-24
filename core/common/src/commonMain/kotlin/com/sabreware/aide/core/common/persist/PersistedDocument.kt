package com.sabreware.aide.core.common.persist

import com.sabreware.aide.core.common.storage.PlatformPaths
import kotlinx.serialization.KSerializer
import okio.Path

/**
 * One typed document the app keeps on disk, declared ONCE: its schema is [serializer] — the `@Serializable`
 * Kotlin class — and nothing else. The file on disk, the ledger entry under `schemas/documents/` and the
 * in-memory value are all derived from that one declaration, so adding a field is editing the class.
 *
 * **[version] is a ledger number, not a migration key.** Any change to the class's shape writes a new
 * `schemas/documents/<name>/<version>.json` (via `PersistedDocumentSchemaTest`) and `schemaCheck` refuses a
 * rewritten old one — the same discipline as Room's exported schemas. Reads are lenient whatever version a
 * file carries (unknown keys ignored, absent keys take their defaults), so an additive change keeps the
 * user's data; a change the lenient decode cannot absorb falls to [durability]'s corruption policy. There
 * is no old→new migration code (pre-release, see CLAUDE.md).
 *
 * Every property of a document class should have a default: that is what makes "a field was added" a
 * non-event for the file already on disk.
 */
class PersistedDocument<T>(
    /** File stem and ledger directory. Lowercase snake_case; never reused for a different class. */
    val name: String,
    val version: Int,
    val serializer: KSerializer<T>,
    /** The value when there is no file yet, and after a reset. */
    val default: T,
    val durability: Durability,
    /**
     * While the file cannot be read (a transient I/O failure — corruption is handled separately), may
     * [default] stand in? True for a document whose default is a harmless starting point (no overrides,
     * nothing imported). False when the default would read as an ANSWER — "the user chose nothing" — and
     * every surface would act on it: such a document stays [DocState.Loading] until a read succeeds.
     */
    val defaultWhileUnreadable: Boolean = true,
) {
    init {
        require(name.matches(NAME)) { "document name must be lowercase snake_case: $name" }
        require(version >= 1) { "document version starts at 1: $name" }
    }

    override fun toString(): String = "PersistedDocument($name v$version)"

    private companion object {
        val NAME = Regex("[a-z][a-z0-9_]*")
    }
}

/**
 * What losing the document costs, which decides where it lives and what a corrupt file does.
 *
 * The split is the one every host already draws — Android `filesDir`/`cacheDir`, XDG data/cache,
 * `Application Support`/`Caches`, `%APPDATA%`/`%LOCALAPPDATA%` — so the OS's own cleanup can only ever
 * touch the half that is safe to lose.
 */
enum class Durability {
    /**
     * Something the user chose. Lives under `filesDir`. A file that cannot be read is COPIED aside
     * (`<name>.corrupt-<epochMs>.json`) before the default replaces it: user intent is never destroyed
     * silently.
     */
    Intent,

    /**
     * Derived from something else and rebuilt on demand. Lives under `cacheDir`; the host may purge it at
     * any time. A file that cannot be read is simply replaced.
     */
    Cache,
}

/**
 * Where [document] lives on this host: `filesDir/documents/` for [Durability.Intent], `cacheDir/documents/`
 * for [Durability.Cache]. The ONE place a document's path is decided — each host's [PlatformPaths] already
 * maps those two roots to its own conventions (Android app dirs, XDG, `Application Support`/`Caches`,
 * `%APPDATA%`/`%LOCALAPPDATA%`), so no document ever names a directory itself.
 */
fun PlatformPaths.documentPath(document: PersistedDocument<*>): Path {
    val root = when (document.durability) {
        Durability.Intent -> filesDir
        Durability.Cache -> cacheDir
    }
    return root / "documents" / "${document.name}.json"
}
