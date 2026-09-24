package com.sabreware.aide.aisdk.providers.prodia

import com.sabreware.aide.aisdk.providers.testing.RecordedCall
import com.sabreware.aide.aisdk.util.MultipartResponse
import com.sabreware.aide.aisdk.util.MultipartResponsePart
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlin.test.assertNotNull
import kotlinx.serialization.json.JsonObject

/**
 * The reference's own fixtures, byte-for-byte.
 *
 * Bodies are built exactly as `createLanguageMultipartResponse` (`prodia-language-model.test.ts`) and
 * `createVideoMultipartResponse` (`prodia-video-model.test.ts`) build them — the same boundary, the
 * same `filename=` on every part, the same place each CRLF falls — and the job JSON is what
 * `JSON.stringify` emits for each file's `defaultJobResult`, integral doubles included (`20`, not
 * `20.0`). Agreeing with these is agreeing with the wire the reference recorded, not with our own
 * reading of it.
 */
internal object ProdiaFixtures {

    const val BOUNDARY: String = "test-boundary-12345"
    const val CONTENT_TYPE: String = "multipart/form-data; boundary=$BOUNDARY"

    // prodia-language-model.test.ts — `defaultJobResult`, `textContent`, `imageContent`
    const val LANGUAGE_JOB: String = """{"id":"job-lang-123","created_at":"2025-01-01T00:00:00Z",""" +
        """"updated_at":"2025-01-01T00:00:03Z","state":{"current":"completed"},""" +
        """"config":{"prompt":"Describe this image","seed":7},"metrics":{"elapsed":1.5,"ips":20},""" +
        """"price":{"product":"nano-banana","dollars":0.01}}"""
    const val LANGUAGE_TEXT: String = "This is a beautiful landscape."
    const val LANGUAGE_IMAGE: String = "test-image-bytes"

    // prodia-language-model.test.ts — `handles API errors`
    const val LANGUAGE_ERROR: String = """{"message":"Bad request","detail":"Missing input image"}"""

    // prodia-video-model.test.ts — `defaultJobResult`, `videoContent`, the CDN body, `handles API errors`
    const val VIDEO_JOB: String = """{"id":"job-vid-123","created_at":"2025-01-01T00:00:00Z",""" +
        """"updated_at":"2025-01-01T00:00:10Z","state":{"current":"completed"},""" +
        """"config":{"prompt":"A cat walking on a beach","seed":99},"metrics":{"elapsed":5,"ips":3.2},""" +
        """"price":{"product":"wan2-2.lightning","dollars":0.05}}"""
    const val VIDEO_CONTENT: String = "test-video-content"
    const val CDN_INPUT: String = "input-image-bytes"
    const val VIDEO_ERROR: String = """{"message":"Invalid prompt","detail":"Prompt cannot be empty"}"""

    /** `createLanguageMultipartResponse`: the job, then an optional `message.txt`, then an optional `image.png`. */
    fun languageResponse(
        job: String = LANGUAGE_JOB,
        text: String? = LANGUAGE_TEXT,
        image: String? = LANGUAGE_IMAGE,
    ): ByteArray {
        val head = buildString {
            append("--$BOUNDARY\r\n")
            append("Content-Disposition: form-data; name=\"job\"; filename=\"job.json\"\r\n")
            append("Content-Type: application/json\r\n")
            append("\r\n")
            append(job)
            append("\r\n")
            if (text != null) {
                append("--$BOUNDARY\r\n")
                append("Content-Disposition: form-data; name=\"output\"; filename=\"message.txt\"\r\n")
                append("Content-Type: text/plain\r\n")
                append("\r\n")
                append(text)
                append("\r\n")
            }
        }.encodeToByteArray()
        val picture = if (image == null) {
            ByteArray(0)
        } else {
            buildString {
                append("--$BOUNDARY\r\n")
                append("Content-Disposition: form-data; name=\"output\"; filename=\"image.png\"\r\n")
                append("Content-Type: image/png\r\n")
                append("\r\n")
            }.encodeToByteArray() + image.encodeToByteArray() + "\r\n".encodeToByteArray()
        }
        return head + picture + "--$BOUNDARY--\r\n".encodeToByteArray()
    }

    /** `createVideoMultipartResponse`: the job, then the clip under the type and filename given. */
    fun videoResponse(
        job: String = VIDEO_JOB,
        video: String = VIDEO_CONTENT,
        contentType: String = "video/mp4",
        fileName: String = "output.mp4",
    ): ByteArray {
        val head = buildString {
            append("--$BOUNDARY\r\n")
            append("Content-Disposition: form-data; name=\"job\"; filename=\"job.json\"\r\n")
            append("Content-Type: application/json\r\n")
            append("\r\n")
            append(job)
            append("\r\n")
            append("--$BOUNDARY\r\n")
            append("Content-Disposition: form-data; name=\"output\"; filename=\"$fileName\"\r\n")
            append("Content-Type: $contentType\r\n")
            append("\r\n")
        }.encodeToByteArray()
        return head + video.encodeToByteArray() + "\r\n--$BOUNDARY--\r\n".encodeToByteArray()
    }
}

// ---------------------------------------------------------------------------------------------------
// Reading a multipart REQUEST back, with the same parser the models use on the answer
// ---------------------------------------------------------------------------------------------------

/**
 * The parts the client sent, split on the boundary its own `Content-Type` declared.
 *
 * The harness reads a request body as text, which round-trips ASCII exactly and nothing else; every
 * byte assertion below therefore uses ASCII bytes, and the one PNG-signature test asserts the type
 * the part was labelled with rather than its bytes.
 */
internal fun RecordedCall.sentParts(): List<MultipartResponsePart> {
    val boundary = MultipartResponse.boundaryOf(header("Content-Type"))
    assertNotNull(boundary, "request should be multipart, was ${header("Content-Type")}")
    return MultipartResponse.parse(bodyText.encodeToByteArray(), boundary)
}

/** The job envelope, decoded from the `job` part. */
internal fun RecordedCall.jobPart(): JsonObject {
    val job = sentParts().firstOrNull { it.name == "job" }
    assertNotNull(job, "no `job` part among ${sentParts().map { it.name }}")
    return parseJsonObject(job.body.decodeToString())
}

/** The picture part, or null when the job went out without one. */
internal fun RecordedCall.inputPart(): MultipartResponsePart? = sentParts().firstOrNull { it.name == "input" }
