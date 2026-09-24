package com.sabreware.aide.aisdk.providers.prodia

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.InvalidResponseDataError
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.RequestInfo
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.ResponseInfo
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.IdGenerator
import com.sabreware.aide.aisdk.util.MediaType
import com.sabreware.aide.aisdk.util.MultipartResponsePart
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Prodia's image job, dressed as a language model.
 *
 * **It is not a chat model, and the shape is the point.** Prodia serves one endpoint — the same
 * `POST {base}/job?price=true` its image and video models use — and this wrapper exists so an
 * image-editing job can be driven from a conversation: the prompt is the instruction, the attached
 * picture is the input, and the result comes back as a file part of the assistant turn. Every genuine
 * LLM knob (temperature, tools, token limits) has nowhere to go, so each is reported as a
 * [Warning.Unsupported] rather than accepted and ignored.
 *
 * **Every request is multipart, picture or not** — the reference does the same, because an
 * `.img2img.` job needs the input as a request part and one request shape means one decoder at the
 * endpoint. A text-only turn simply has no `input` part.
 *
 * The response is MULTIPART, decoded the same way as the image model's: a JSON part named `job` and
 * `output` parts that are text, an image, or both.
 */
internal class ProdiaLanguageModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val apiKey: String,
    private val generateId: () -> String = IdGenerator("txt_")::next,
) : LanguageModel {

    override val provider: String = PRODIA_PROVIDER_ID

    override suspend fun doGenerate(options: CallOptions): GenerateResult {
        val warnings = options.unsupportedWarnings().toMutableList()
        val vendor = options.providerOptions?.get(PRODIA_PROVIDER_ID)
        val input = options.prompt.resolveInputImage(warnings)

        val jobJson = buildJsonObject {
            put("type", modelId)
            put(
                "config",
                buildJsonObject {
                    put("prompt", options.prompt.toProdiaPrompt())
                    // The reference sets this unconditionally; it is what makes the job answer with the
                    // text part this model reads back as content.
                    put("include_messages", true)
                    vendor?.get("aspectRatio")?.let { put("aspect_ratio", it) }
                },
            )
        }.toProdiaJobJson()

        val result = http.postMultipartForBytes(
            url = "$baseUrl/job?price=true",
            parts = prodiaJobParts(jobJson, input),
            headers = combineHeaders(
                mapOf("Authorization" to "Bearer $apiKey"),
                options.headers,
                // LAST, so a caller cannot override it — see the note on the image model.
                mapOf("Accept" to "multipart/form-data"),
            ),
        )

        val parts = result.value.prodiaParts(result.headers["content-type"])
        val job = parts.firstOrNull { it.name == "job" }
            ?.let { parseJsonObject(it.body.decodeToString()) }
            ?: throw InvalidResponseDataError("Prodia multipart response missing job part")

        return GenerateResult(
            content = parts.filter { it.name == "output" }.toContent(),
            // Prodia has no notion of a finish reason: a job either produced its output or failed the
            // request outright, so a completed one always stopped normally.
            finishReason = FinishReason(FinishReason.Unified.Stop),
            // No token counts anywhere on this wire; an unreported count stays null rather than zero.
            usage = Usage(),
            warnings = warnings,
            providerMetadata = mapOf(PRODIA_PROVIDER_ID to job.toJobMetadata()),
            // The transport holds no string form of a multipart body, so the envelope is quoted here:
            // it is the half of the request a wire-level bug report can actually read.
            request = RequestInfo(body = jobJson),
            response = result.responseInfo(
                modelId = modelId,
                id = job["id"]?.jsonPrimitive?.content,
            ),
        )
    }

    /**
     * The whole answer, replayed as stream parts.
     *
     * There is nothing to stream: the endpoint blocks until the job is done and answers once. Emitting
     * the finished result as parts means a caller written against `doStream` works here without
     * learning that this one provider is different.
     */
    override suspend fun doStream(options: CallOptions): StreamResult {
        val generated = doGenerate(options)
        return StreamResult(
            stream = flow {
                emit(StreamPart.StreamStart(generated.warnings))
                generated.response?.let { emit(StreamPart.ResponseMetadataPart(it.metadata)) }
                for (part in generated.content) {
                    when (part) {
                        is Content.Text -> {
                            val id = generateId()
                            emit(StreamPart.TextStart(id))
                            emit(StreamPart.TextDelta(id, part.text))
                            emit(StreamPart.TextEnd(id))
                        }
                        is Content.File -> emit(StreamPart.FilePart(part))
                        else -> Unit
                    }
                }
                emit(
                    StreamPart.Finish(
                        usage = generated.usage,
                        finishReason = generated.finishReason,
                        providerMetadata = generated.providerMetadata,
                    ),
                )
            },
            request = generated.request,
            response = generated.response?.let { ResponseInfo(headers = it.headers) },
        )
    }

    /**
     * The input picture: the first image attached to the LAST user turn, as bytes we hold.
     *
     * A vendor-held reference and an inline text document are refused outright, as the reference
     * refuses them — there is no request part either could become. A link is downloaded here, through
     * the same guard every other download in this module goes through; the reference fetches it with a
     * bare `fetch`, and a prompt-supplied URL that can name a loopback or a metadata service is the
     * one input this library must not fetch unchecked.
     *
     * A second image in the same turn is NOT sent. The job takes one `input`, the reference silently
     * keeps the first, and this module's standing rule is to say so where the reference drops silently.
     */
    private suspend fun List<ModelMessage>.resolveInputImage(warnings: MutableList<Warning>): ProdiaInput? {
        val user = lastOrNull { it is ModelMessage.User } as? ModelMessage.User ?: return null
        val images = user.content.filterIsInstance<UserPart.File>()
            .filter { it.mediaType.topLevelMediaType() == "image" }
        val first = images.firstOrNull() ?: return null
        if (images.size > 1) {
            warnings += Warning.Unsupported(
                "multiple image inputs",
                "Prodia takes one input picture per job; the first of ${images.size} was sent.",
            )
        }
        val bytes = when (val data = first.data) {
            is FileData.Reference ->
                throw UnsupportedFunctionalityError("file parts with provider references")
            is FileData.Text -> throw UnsupportedFunctionalityError("text file parts")
            is FileData.Bytes -> data.bytes
            is FileData.Url -> http.getBytes(data.url).value
        }
        // A full type is trusted as given; a bare top-level `image` is sniffed from the bytes, and
        // falls back to PNG when the signature is unknown — the reference's default, and the type the
        // endpoint accepts most widely.
        val mediaType = if (first.mediaType.isFullMediaType()) {
            first.mediaType
        } else {
            MediaType.detect(bytes, first.mediaType.topLevelMediaType()) ?: "image/png"
        }
        return ProdiaInput(bytes, mediaType)
    }
}

/**
 * The `output` parts as content: text first, then every image — the reference's order, whatever the
 * wire's.
 *
 * A text part is recognised by its type OR by a `.txt` filename, because the endpoint has labelled the
 * message part both ways. Only the LAST text part survives, as in the reference, where the message is
 * a single field rather than a list.
 */
private fun List<MultipartResponsePart>.toContent(): List<Content> {
    var text: String? = null
    val files = mutableListOf<Content>()
    for (part in this) {
        val type = part.contentType.orEmpty()
        val disposition = part.headers["content-disposition"].orEmpty()
        when {
            type.startsWith("text/") || disposition.contains(".txt") -> text = part.body.decodeToString()
            type.startsWith("image/") -> files += Content.File(mediaType = type, data = FileData.Bytes(part.body))
        }
    }
    return listOfNotNull(text?.let { Content.Text(it) }) + files
}

/**
 * The system turn and the LAST user turn's text, joined — which is all a job config has room for.
 *
 * Earlier turns are dropped rather than concatenated: a job takes one instruction, and pasting a whole
 * conversation into it produces an image of the transcript.
 */
private fun List<ModelMessage>.toProdiaPrompt(): String {
    val system = filterIsInstance<ModelMessage.System>().lastOrNull()?.content
    val user = lastOrNull { it is ModelMessage.User } as? ModelMessage.User
    val text = user?.content.orEmpty()
        .filterIsInstance<UserPart.Text>()
        .joinToString("\n") { it.text }
    return listOfNotNull(system?.takeIf { it.isNotEmpty() }, text.takeIf { it.isNotEmpty() })
        .joinToString("\n")
}

/** `image` for `image/png`, and the whole string when there is no slash — the reference's rule. */
private fun String.topLevelMediaType(): String = substringBefore('/')

/** A type with a real subtype: `image/png` yes; `image`, `image/` and a wildcard no. */
private fun String.isFullMediaType(): Boolean {
    val subtype = substringAfter('/', missingDelimiterValue = "")
    return subtype.isNotEmpty() && subtype != "*"
}

/** Every LLM knob this endpoint has no room for, each named rather than silently dropped. */
private fun CallOptions.unsupportedWarnings(): List<Warning> = buildList {
    if (temperature != null) add(Warning.Unsupported("temperature"))
    if (topP != null) add(Warning.Unsupported("topP"))
    if (topK != null) add(Warning.Unsupported("topK"))
    if (seed != null) add(Warning.Unsupported("seed"))
    if (maxOutputTokens != null) add(Warning.Unsupported("maxOutputTokens"))
    if (stopSequences != null) add(Warning.Unsupported("stopSequences"))
    if (presencePenalty != null) add(Warning.Unsupported("presencePenalty"))
    if (frequencyPenalty != null) add(Warning.Unsupported("frequencyPenalty"))
    if (!tools.isNullOrEmpty()) add(Warning.Unsupported("tools"))
    if (toolChoice != null) add(Warning.Unsupported("toolChoice"))
    if (responseFormat != null && responseFormat !is ResponseFormat.Text) {
        add(Warning.Unsupported("responseFormat"))
    }
    if (reasoning != ReasoningEffort.ProviderDefault) {
        add(Warning.Unsupported("reasoning", "This provider does not support reasoning configuration."))
    }
}
