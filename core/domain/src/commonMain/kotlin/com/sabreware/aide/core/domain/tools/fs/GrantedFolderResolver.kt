package com.sabreware.aide.core.domain.tools.fs

data class ResolvedFolder(val displayName: String, val absolutePath: String)

interface GrantedFolderResolver {
    /** Resolve a picked tree uri (opaque platform string) to a display name + absolute path on primary
     *  storage, or null if unusable. */
    suspend fun resolve(treeUri: String): ResolvedFolder?
}
