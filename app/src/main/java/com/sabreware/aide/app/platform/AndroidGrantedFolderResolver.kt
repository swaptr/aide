package com.sabreware.aide.app.platform

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.sabreware.aide.core.domain.tools.fs.GrantedFolderResolver
import com.sabreware.aide.core.domain.tools.fs.ResolvedFolder
import java.io.File

class AndroidGrantedFolderResolver(private val context: Context) : GrantedFolderResolver {

    // Non-primary trees (SD/USB) unsupported in v1.
    override suspend fun resolve(treeUri: String): ResolvedFolder? {
        val uri = Uri.parse(treeUri)
        val tree = DocumentFile.fromTreeUri(context, uri)
        val displayName = tree?.name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment.orEmpty().substringAfterLast(':').ifBlank { "Folder" }
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        val absolutePath = docId?.let { absolutePathFor(it) } ?: return null
        val dir = File(absolutePath)
        if (!dir.exists() || !dir.isDirectory) return null
        return ResolvedFolder(displayName = displayName, absolutePath = absolutePath)
    }

    private fun absolutePathFor(docId: String): String? {
        // "primary:Foo/Bar" → /storage/emulated/0/Foo/Bar; non-primary volumes unreliable for File access.
        if (!docId.startsWith("primary:")) return null
        val rest = docId.removePrefix("primary:")
        val base = Environment.getExternalStorageDirectory().absolutePath
        return if (rest.isEmpty()) base else "$base/$rest"
    }
}
