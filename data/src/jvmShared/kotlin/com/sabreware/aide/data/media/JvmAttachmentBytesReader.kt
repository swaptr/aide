package com.sabreware.aide.data.media

import com.sabreware.aide.core.common.media.AttachmentBytesReader
import java.io.File

/**
 * The one attachment-bytes reader.
 *
 * There were two, in `:app` and `:desktopApp`, and they were the same two lines — `File(path).readBytes()`
 * on both, because both targets are the JVM. That is the shape the jvmShared source directory exists for:
 * code that needs the JVM and is identical on Android and desktop. Each application binds it; neither owns
 * a copy of it.
 */
class JvmAttachmentBytesReader : AttachmentBytesReader {
    override fun read(path: String): ByteArray? =
        runCatching { File(path).readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() }
}
