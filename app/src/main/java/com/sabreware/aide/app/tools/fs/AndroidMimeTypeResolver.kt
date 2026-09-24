package com.sabreware.aide.app.tools.fs

import android.webkit.MimeTypeMap
import com.sabreware.aide.data.tools.fs.MimeTypeResolver
import java.io.File

/**
 * Android's extension→MIME table, the one platform difference in the shared `JvmFileSystemBackend`. NIO's
 * probe (the default resolver) is near-useless here: Android ships no content-type detector, so it answers
 * null for almost everything.
 */
object AndroidMimeTypeResolver : MimeTypeResolver {
    override fun mimeTypeFor(file: File): String? {
        val ext = file.name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }
}
