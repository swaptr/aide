package com.sabreware.aide.core.domain.video

/**
 * Generates video, as two calls rather than one.
 *
 * **Why the job is split.** A generation runs for minutes. A single `generate()` that hid the waiting
 * would tie the result to one process staying alive: close the app mid-render and the job becomes
 * unreachable, because the only handle to it was a local variable. So [start] returns an opaque,
 * storable [VideoJob] and [status] takes it back — the app persists the handle and can resume after a
 * restart, which is the difference between a render the user gets and a render they paid for and lost.
 *
 * The handle is deliberately opaque: what it holds is the vendor's business, and a typed one would make
 * this layer know what a task id looks like at every vendor it might ever reach.
 */
interface VideoEngine {
    suspend fun start(
        modelName: String,
        prompt: String,
        options: VideoOptions = VideoOptions(),
    ): VideoJob

    suspend fun status(job: VideoJob): VideoStatus
}

/**
 * A running generation, storable as text so it survives the process that started it.
 *
 * [providerId] rides along because a handle is meaningless to the vendor that did not issue it, and a
 * persisted job outlives the memory of which provider was selected when it began.
 */
data class VideoJob(val providerId: String, val handle: String)

data class VideoOptions(
    /** `W:H`, e.g. `16:9`. */
    val aspectRatio: String? = null,
    val durationInSeconds: Double? = null,
    val seed: Int? = null,
)

/**
 * Where a generation has got to.
 *
 * [Failed] is a status rather than an exception because a refused prompt is an answer: the caller must be
 * able to tell "this will be refused every time" from "the status call did not go through", and
 * collapsing them means retrying something that can only ever fail again.
 */
sealed interface VideoStatus {
    data object Pending : VideoStatus
    data class Ready(val videos: List<GeneratedVideo>) : VideoStatus
    data class Failed(val reason: String) : VideoStatus
}

/**
 * One finished clip.
 *
 * URL comes first because it is what almost every vendor returns: a finished video is tens of megabytes,
 * and inlining that in a JSON response is not something a serious API does.
 */
sealed interface GeneratedVideo {
    val mediaType: String

    data class Url(val url: String, override val mediaType: String) : GeneratedVideo

    data class Bytes(val bytes: ByteArray, override val mediaType: String) : GeneratedVideo {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is Bytes && mediaType == other.mediaType && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + mediaType.hashCode()
    }
}
