package com.sabreware.aide.core.common.media

/**
 * Reads a staged attachment's bytes, for the wire codecs that have to inline an image.
 *
 * A named port rather than the `(String) -> ByteArray?` it replaces. Function types erase, so the lambda
 * could only be told apart from any other `(String) -> ByteArray?` by a DI qualifier — a string, checked at
 * runtime, holding together a seam the type system was supposed to hold. A port with a name is the same
 * amount of code and cannot be resolved to the wrong thing.
 *
 * The argument is an attachment path this app produced (via [ImageAttachmentStore] or [FileAttachmentStore]),
 * NOT a picked platform URI: those are interpreted differently per platform and never reach here.
 *
 * **Not suspending, unlike [com.sabreware.aide.core.domain.model.ModelStorage].** That port had to become
 * suspending because a caller was invoking it on `Main.immediate`. This one is reached only from a wire
 * codec's message mapping, which runs inside a session stream already carried on an IO dispatcher — so the
 * blocking read is on a thread that expects it, and a suspending signature here would buy nothing while
 * forcing the codecs to build their requests lazily.
 */
fun interface AttachmentBytesReader {
    /** The file's bytes, or null when it is missing, empty or unreadable. Never throws. */
    fun read(path: String): ByteArray?
}
