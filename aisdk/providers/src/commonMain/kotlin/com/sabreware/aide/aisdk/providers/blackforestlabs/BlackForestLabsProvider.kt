package com.sabreware.aide.aisdk.providers.blackforestlabs

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageFile
import com.sabreware.aide.aisdk.ImageModel
import com.sabreware.aide.aisdk.ImageResult
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.VideoModel
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.DownloadUrl
import com.sabreware.aide.aisdk.util.JobStatus
import com.sabreware.aide.aisdk.util.PollPolicy
import com.sabreware.aide.aisdk.util.ProviderErrorStructure
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.pollUntilDone
import io.ktor.client.HttpClient
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The provider id, and the namespace Black Forest Labs payloads file under. */
public const val BFL_PROVIDER_ID: String = "blackForestLabs"

/**
 * Black Forest Labs (FLUX) image generation.
 *
 * Queue-based, with three details that catch clients out. Auth is the header `x-key` — not a bearer, not
 * an api-key. The submit answers with the URL to poll (`polling_url`), on a DIFFERENT host from the
 * submit endpoint, so a client that rebuilds the URL from its own base gets a 404 for a job that ran
 * fine. And that poll URL needs the request id appended: BFL routes a poll by query parameter, not by
 * path, and a poll without `?id=` answers about somebody else's job or about nothing.
 *
 * Its terminal statuses are also more informative than most: `Request Moderated` and `Content Moderated`
 * distinguish a rejected prompt from a rejected result. Flattening them loses the only thing that tells
 * a caller whether retrying this prompt could ever work.
 */
public class BlackForestLabsProvider(
    client: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val pollPolicy: PollPolicy = PollPolicy(),
    private val elapsedMillis: () -> Long,
) : Provider {

    override val providerId: String = BFL_PROVIDER_ID

    private val http = ProviderHttp(client).withErrorStructure(BlackForestLabsErrors)

    override fun imageModel(modelId: String): ImageModel = BlackForestLabsImageModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        apiKey = apiKey,
        pollPolicy = pollPolicy,
        elapsedMillis = elapsedMillis,
    )

    /**
     * FLUX 3 video. No poll policy here, deliberately: video uses the `doStart`/`doStatus` contract,
     * where polling belongs to the caller — the image model's internal loop is the older shape, kept
     * because an image is seconds where a video is minutes.
     */
    override fun videoModel(modelId: String): VideoModel = BlackForestLabsVideoModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        apiKey = apiKey,
    )

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.bfl.ai/v1"
    }
}

/** BFL puts its message under `detail` — sometimes a string, sometimes a structure — before `message`. */
internal val BlackForestLabsErrors: ProviderErrorStructure = ProviderErrorStructure(
    extractMessage = { body ->
        val obj = body as? JsonObject
        when (val detail = obj?.get("detail")) {
            null -> obj?.get("message")?.jsonPrimitive?.content
            is JsonPrimitive -> if (detail.isString) detail.content else detail.toString()
            else -> detail.toString()
        }
    },
)

/**
 * Whether the API key may ride along to a URL BFL itself named.
 *
 * Strict same-origin is wrong here, and this is the one vendor where that is not a shortcut. BFL answers
 * a submit on `api.bfl.ai` with a polling URL on the cluster that picked the job up — `api.us1.bfl.ai`,
 * `api.eu1.bfl.ai` — so a same-origin check drops the key on every poll and every job 401s. The trust
 * boundary is therefore the `bfl.ai` domain over HTTPS, not the configured base URL.
 *
 * It stops at the domain rather than at a suffix match on the string: `bfl.ai.attacker.net` ends with
 * the right characters and is somebody else's site, which is the whole reason the check is written as a
 * host comparison.
 *
 * The host comes from [DownloadUrl.hostOf] rather than from a parse written here. This function once had
 * its own, and it stripped the port before the userinfo — so `https://bfl.ai:x@evil.com/poll`, a URL the
 * vendor response is free to name, read as host `bfl.ai`, passed this check, and took the API key to
 * `evil.com`. Two parsers is how one of them ends up wrong.
 */
internal fun isTrustedBflUrl(url: String, baseUrl: String): Boolean {
    if (DownloadUrl.sameOrigin(url, baseUrl)) return true
    if (!url.startsWith("https://")) return false
    val host = DownloadUrl.hostOf(url) ?: return false
    return host == "bfl.ai" || host.endsWith(".bfl.ai")
}

/**
 * A file as BFL wants it: a URL, or BARE base64 with no `data:` prefix.
 *
 * The prefix is a 422 naming the image field, which reads as "this picture is unusable" rather than
 * "this envelope is".
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun ImageFile.toBflImage(): String = when (this) {
    is ImageFile.Url -> url
    is ImageFile.Data -> when (val payload = data) {
        is BinaryData.Base64 -> payload.value
        is BinaryData.Bytes -> Base64.encode(payload.value)
    }
}

/** BFL's own documented ceiling; the eleventh image is a rejection, not a truncation. */
private const val MAX_INPUT_IMAGES = 10

/** The one model that spells the input image `image`; everything else spells it `input_image`. */
private const val FILL_MODEL_ID = "flux-pro-1.0-fill"

internal class BlackForestLabsImageModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val apiKey: String,
    private val pollPolicy: PollPolicy,
    private val elapsedMillis: () -> Long,
) : ImageModel {

    override val provider: String = BFL_PROVIDER_ID

    override suspend fun maxImagesPerCall(): Int = 1

    // Not a bearer, and not `api-key`.
    private val authHeaders get() = mapOf("x-key" to apiKey)

    override suspend fun doGenerate(options: ImageCallOptions): ImageResult {
        val warnings = mutableListOf<Warning>()
        val bflOptions = options.providerOptions?.get(BFL_PROVIDER_ID)
        if (options.size != null) {
            warnings += Warning.Unsupported(
                "size",
                if (options.aspectRatio == null) {
                    "Deriving aspect_ratio from size. Use the width and height provider options to " +
                        "specify dimensions for models that support them."
                } else {
                    "Black Forest Labs ignores size when aspectRatio is provided. Use the width and " +
                        "height provider options to specify dimensions for models that support them"
                },
            )
        }

        val files = options.files.orEmpty()
        if (files.size > MAX_INPUT_IMAGES) {
            throw InvalidArgumentError(
                message = "Black Forest Labs supports up to $MAX_INPUT_IMAGES input images.",
                argument = "files",
            )
        }
        val imageField = if (modelId == FILL_MODEL_ID) "image" else "input_image"
        val dimensions = options.size?.split("x")?.takeIf { it.size == 2 }

        val headers = combineHeaders(authHeaders, options.headers)
        val submitted = http.postJson(
            url = "$baseUrl/$modelId",
            body = buildJsonObject {
                options.prompt?.let { put("prompt", it) }
                options.seed?.let { put("seed", it) }
                (options.aspectRatio ?: options.size?.let(::aspectRatioOf))?.let {
                    put("aspect_ratio", it)
                }
                // A caller's explicit width/height wins over the pair derived from `size`: the derived
                // pair is a best effort at a ratio, while these are the exact pixels the model was asked
                // for.
                (bflOptions?.get("width") ?: dimensions?.get(0)?.toIntOrNull()?.let(::JsonPrimitive))
                    ?.let { put("width", it) }
                (bflOptions?.get("height") ?: dimensions?.get(1)?.toIntOrNull()?.let(::JsonPrimitive))
                    ?.let { put("height", it) }
                files.forEachIndexed { index, file ->
                    put(if (index == 0) imageField else "${imageField}_${index + 1}", file.toBflImage())
                }
                options.mask?.let { put("mask", it.toBflImage()) }
                bflOptions?.forEach { (key, value) ->
                    when {
                        key in BFL_HANDLED_OPTIONS -> Unit
                        // BFL's submit body is a CLOSED schema: an unexpected key is a 422 naming the
                        // field, not a parameter the service politely ignores. So an option this vendor
                        // has no field for is dropped rather than passed through the way fal's and
                        // Replicate's are — and the caller is told, because a silently dropped option
                        // looks exactly like one that was applied and had no effect.
                        key in BFL_FIELD_NAMES || key in BFL_PASSTHROUGH_OPTIONS ->
                            put(BFL_FIELD_NAMES[key] ?: key, value)
                        else -> warnings += Warning.Unsupported(
                            key,
                            "Black Forest Labs has no request field for this option, and rejects a " +
                                "body carrying one it does not know.",
                        )
                    }
                }
            },
            headers = headers,
        )
        val submitBody = submitted.value.jsonObject

        // The URL to poll comes from the response and is on a DIFFERENT host from the submit endpoint;
        // rebuilding it from our own base is a 404 for a job that ran perfectly well.
        val pollingUrl = submitBody["polling_url"]?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("Black Forest Labs returned no polling_url")
        // BFL routes a poll by query parameter. Without `?id=` the request answers about no job at all,
        // so it is appended here — and only when the URL does not already carry one, because the vendor
        // sometimes includes it and a second `id` is a rejected request.
        val requestId = submitBody["id"]?.jsonPrimitive?.content
        val pollUrl = when {
            requestId == null || "id=" in pollingUrl.substringAfter('?', "") -> pollingUrl
            '?' in pollingUrl -> "$pollingUrl&id=$requestId"
            else -> "$pollingUrl?id=$requestId"
        }
        DownloadUrl.validate(pollUrl)
        val pollHeaders = if (isTrustedBflUrl(pollUrl, baseUrl)) headers else emptyMap()

        val ready = pollUntilDone(policy = pollPolicy, elapsedMillis = elapsedMillis) {
            val task = http.getJson(pollUrl, pollHeaders).value.jsonObject
            // `state` is the same field under a second name; a client reading only `status` sees null and
            // treats every poll as an unknown terminal status.
            val status = (task["status"] ?: task["state"])?.jsonPrimitive?.content
            when (status) {
                // `as? JsonObject`, not `.jsonObject`: BFL answers a Ready poll that produced nothing
                // with `"result": null`, and the throwing accessor turns that documented shape into a
                // raw IllegalArgumentException about JsonNull — an internal error where the vendor was
                // being perfectly clear.
                "Ready" -> (task["result"] as? JsonObject)
                    ?.takeIf { it["sample"]?.jsonPrimitive?.content != null }
                    ?.let { JobStatus.Succeeded(it) }
                    ?: JobStatus.Failed("Black Forest Labs reported Ready with no result sample")
                "Pending" -> JobStatus.InProgress()
                // Worth keeping distinct: one is a rejected prompt — which will be rejected again on
                // every retry — and the other a rejected result, which a re-roll may well pass.
                "Request Moderated" -> JobStatus.Failed("The prompt was rejected by moderation")
                "Content Moderated" -> JobStatus.Failed("The generated image was rejected by moderation")
                // BFL parses a `details` field on the poll body and the reference never reads it, so a
                // failed job reports only that it failed. What went wrong is right there.
                "Error", "Failed" -> JobStatus.Failed(
                    "Black Forest Labs generation failed." +
                        (task["details"]?.failureDetail()?.let { " $it" } ?: ""),
                )
                else -> JobStatus.Failed("Black Forest Labs reported status $status")
            }
        }

        val imageUrl = ready.getValue("sample").jsonPrimitive.content
        val image = http.getBytes(
            url = imageUrl,
            headers = headers,
            // The delivery URL is usually a CDN on a foreign host, where the key must not go; when it is
            // a bfl.ai cluster host it must, or the fetch 401s.
            trustedOrigin = if (isTrustedBflUrl(imageUrl, baseUrl)) imageUrl else null,
        )

        return ImageResult(
            // Fetched rather than returned as a link: BFL's result URLs expire.
            images = listOf(BinaryData.Bytes(image.value)),
            warnings = warnings,
            // The two halves of the metadata come from two different responses: the seed and timings are
            // only in the poll result, the billing figures only in the submit.
            providerMetadata = mapOf(
                BFL_PROVIDER_ID to buildJsonObject {
                    put(
                        "images",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    ready["seed"]?.let { put("seed", it) }
                                    ready["start_time"]?.let { put("start_time", it) }
                                    ready["end_time"]?.let { put("end_time", it) }
                                    ready["duration"]?.let { put("duration", it) }
                                    // `notNull()`, because BFL fills these with JSON null on the tiers
                                    // that do not bill rather than omitting them. Carried through, a
                                    // present-but-null `cost` reads as a generation that was free.
                                    submitBody["cost"].notNull()?.let { put("cost", it) }
                                    submitBody["input_mp"].notNull()?.let { put("inputMegapixels", it) }
                                    submitBody["output_mp"].notNull()
                                        ?.let { put("outputMegapixels", it) }
                                },
                            )
                        },
                    )
                },
            ),
            response = image.modalityResponse(modelId = modelId, id = requestId),
        )
    }
}

/** JSON null is a value the vendor sent, not a value it has; this is the difference. */
private fun kotlinx.serialization.json.JsonElement?.notNull():
    kotlinx.serialization.json.JsonElement? =
    this?.takeIf { it !is kotlinx.serialization.json.JsonNull }

/** Options spent on the request itself rather than passed to the model. */
private val BFL_HANDLED_OPTIONS = setOf("width", "height", "pollIntervalMillis", "pollTimeoutMillis")

/** Options BFL's own schema names and that need no renaming. */
private val BFL_PASSTHROUGH_OPTIONS = setOf("steps", "guidance", "raw")

private val BFL_FIELD_NAMES = mapOf(
    "imagePrompt" to "image_prompt",
    "imagePromptStrength" to "image_prompt_strength",
    "outputFormat" to "output_format",
    "promptUpsampling" to "prompt_upsampling",
    "safetyTolerance" to "safety_tolerance",
    "webhookSecret" to "webhook_secret",
    "webhookUrl" to "webhook_url",
)

/**
 * BFL takes a ratio, never a pixel size, so `1024x768` has to become `4:3`.
 *
 * Reducing by the greatest common divisor rather than picking the nearest named ratio: BFL accepts any
 * `W:H`, and snapping to a preset would silently crop what the caller asked for.
 */
private fun aspectRatioOf(size: String): String? {
    val parts = size.split("x").takeIf { it.size == 2 } ?: return null
    val width = parts[0].toIntOrNull()?.takeIf { it > 0 } ?: return null
    val height = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
    val divisor = gcd(width, height)
    return "${width / divisor}:${height / divisor}"
}

private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

/**
 * BFL's `details` is `unknown` on the wire: a plain sentence on some failures, a structure on others.
 *
 * Rendering the structure rather than dropping it is deliberate — an unreadable JSON fragment in the
 * message still names the field that was rejected, where an empty message names nothing.
 */
private fun kotlinx.serialization.json.JsonElement.failureDetail(): String? = when (this) {
    is JsonPrimitive -> if (isString) content.takeIf { it.isNotBlank() } else toString()
    else -> toString().takeIf { it != "{}" && it != "[]" }
}
