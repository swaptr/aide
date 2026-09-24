package com.sabreware.aide.aisdk.providers.prodia

import com.sabreware.aide.aisdk.InvalidResponseDataError
import com.sabreware.aide.aisdk.util.MultipartPart
import com.sabreware.aide.aisdk.util.MultipartResponse
import com.sabreware.aide.aisdk.util.MultipartResponsePart
import com.sabreware.aide.aisdk.util.ProviderJson
import kotlinx.serialization.json.JsonObject

/**
 * The picture a job starts from, as its request part carries it: the bytes and the type the part is
 * labelled with. The label matters — the endpoint keys its decoder off it, and the filename below is
 * derived from it.
 */
internal class ProdiaInput(val bytes: ByteArray, val mediaType: String)

/**
 * The job envelope serialized the way every Prodia request is, so the multipart `job` part and the
 * plain JSON body of a text-only job are byte-identical for the same config.
 */
internal fun JsonObject.toProdiaJobJson(): String = ProviderJson.encodeToString(JsonObject.serializer(), this)

/**
 * A job with an input picture, as multipart parts: the envelope as a JSON part named `job`, then the
 * picture as a part named `input`.
 *
 * Both names, both filenames and the order are the reference's, and none is decorative. The endpoint
 * reads the parts by NAME — `job` for the config, `input` for the picture — so a part sent as `image`
 * is not a 400 but a job run from the prompt alone, which returns a plausible picture of the wrong
 * thing. The filename carries the extension the vendor infers a format from where the content type
 * is not enough, and an image type outside the reference's table goes out bare rather than under a
 * guessed extension.
 */
internal fun prodiaJobParts(jobJson: String, input: ProdiaInput?): List<MultipartPart> = buildList {
    add(MultipartPart.File("job", "job.json", jobJson.encodeToByteArray(), "application/json"))
    if (input != null) {
        add(
            MultipartPart.File(
                field = "input",
                fileName = "input" + prodiaInputExtension(input.mediaType),
                bytes = input.bytes,
                contentType = input.mediaType,
            ),
        )
    }
}

/** The extension the reference spells an input filename with. Anything else is sent without one. */
internal fun prodiaInputExtension(mediaType: String): String = when (mediaType) {
    "image/png" -> ".png"
    "image/jpeg" -> ".jpg"
    "image/webp" -> ".webp"
    "video/mp4" -> ".mp4"
    "video/webm" -> ".webm"
    else -> ""
}

/** The multipart body, or a failure naming the header that should have described it. */
internal fun ByteArray.prodiaParts(contentType: String?): List<MultipartResponsePart> {
    val boundary = MultipartResponse.boundaryOf(contentType)
        ?: throw InvalidResponseDataError(
            "Prodia response missing multipart boundary in content-type: $contentType",
        )
    return MultipartResponse.parse(this, boundary)
}

/**
 * The type a downloaded input is labelled with: the server's own `content-type`, parameters dropped,
 * else what the caller said the link was, else the octet-stream the reference falls back to.
 *
 * The reference reads only the response and defaults straight to octet-stream; a caller who named the
 * type on the link is consulted first here because that is information the reference throws away.
 */
internal fun downloadedMediaType(contentType: String?, declared: String?): String =
    contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
        ?: declared
        ?: "application/octet-stream"
