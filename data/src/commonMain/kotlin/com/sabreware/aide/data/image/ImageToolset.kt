package com.sabreware.aide.data.image

import com.sabreware.aide.core.common.media.FileAttachmentStore
import com.sabreware.aide.core.domain.image.ImageModelCatalog
import com.sabreware.aide.core.domain.image.awaitById
import com.sabreware.aide.core.domain.image.awaitDefault
import com.sabreware.aide.core.domain.image.ImageOptions
import com.sabreware.aide.core.domain.image.ImageProviderRegistry
import com.sabreware.aide.core.domain.image.ImageResult
import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.llm.Surface
import com.sabreware.aide.core.domain.llm.ToolEnvelope
import com.sabreware.aide.core.domain.model.ImageModelSpec
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.model.ModelSelectionStore
import com.sabreware.aide.core.domain.model.activeModelFor
import com.sabreware.aide.core.domain.tools.ToolCategory
import com.sabreware.aide.core.domain.tools.Toolset
import com.sabreware.aide.core.domain.tools.ToolsetScope
import com.sabreware.aide.core.domain.tools.objectSchema
import com.sabreware.aide.core.domain.tools.stringProp
import com.sabreware.aide.core.domain.util.AideLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Image generation as a governed tool, which is what makes it work everywhere at once: it is an
 * `AideTool.Function`, so it flows through the existing `ToolDispatcher` (idempotency, rate limit, trace,
 * write confirm) and both tool loops, and *any* chat model — on-device Gemma, Claude, Gemini — can call it.
 * The chat model and the image model are independent.
 *
 * A dedicated "create image" surface would be a second consumer of the same [ImageEngine]; it is not built,
 * because nothing has asked for it yet.
 */
class ImageToolset(
    private val providers: ImageProviderRegistry,
    private val catalog: ImageModelCatalog,
    private val selection: ModelSelectionStore,
    private val attachments: FileAttachmentStore,
) : Toolset {

    override val category = ToolCategory.Image
    override val displayName = "Image generation"
    override val blurb = "Generate images from a description."

    /** One broad tool the average turn does not need — worth a RequestToolset round trip. */
    override val onDemand = true

    override fun tools(scope: ToolsetScope): List<AideTool> {
        // Nothing to offer if no provider was contributed or no model can be resolved: a tool the model
        // cannot successfully call is worse than no tool.
        if (providers.isEmpty() || catalog.all.isEmpty()) return emptyList()
        return listOf(generateTool())
    }

    override fun promptNotes(scope: ToolsetScope): String? =
        "Image generation:\n" +
            "- `GenerateImage` returns a file path, not the image itself. Refer to it in your reply; the " +
            "user sees the picture.\n" +
            "- Describe subject, style and composition in the prompt; the image model sees nothing of " +
            "this conversation.\n"

    private fun generateTool(): AideTool = AideTool.Function(
        name = "GenerateImage",
        description = "Generate an image from a text description and attach it to the conversation. " +
            "Use it when the user asks for a picture, illustration, diagram or logo. The image model " +
            "sees only the prompt you pass, so describe the subject, style and composition in full.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "prompt" to stringProp(
                    "Full description of the image to draw, e.g. 'a watercolour fox asleep on a " +
                        "windowsill, soft morning light'.",
                ),
            ),
            optionalProps = listOf(
                "size" to stringProp("Output size, e.g. '1024x1024'. Omit for the model's default."),
            ),
        ),
        handler = { args ->
            val prompt = args["prompt"]?.jsonPrimitive?.content.orEmpty()
            if (prompt.isBlank()) {
                return@Function ToolEnvelope.failure("INVALID_ARGS", "prompt is required")
            }
            val spec = resolveModel()
                ?: return@Function ToolEnvelope.failure("PROVIDER_UNAVAILABLE", "no connection serves image models")
            val provider = providers.await(spec.provider)
                ?: return@Function ToolEnvelope.failure(
                    "PROVIDER_UNAVAILABLE",
                    "the connection for ${spec.displayName} is gone",
                )
            val size = args["size"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            if (size != null && spec.capabilities.sizes.isNotEmpty() && size !in spec.capabilities.sizes) {
                return@Function ToolEnvelope.failure(
                    "INVALID_ARGS",
                    "${spec.displayName} accepts sizes: ${spec.capabilities.sizes.joinToString()}",
                )
            }

            AideLog.i(TAG, "GenerateImage called: model=${spec.id}")
            val results = try {
                provider.image.generate(spec.remoteName!!, prompt, ImageOptions(size = size))
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // Provider moderation rejections land here; the model should see the reason and adapt.
                return@Function ToolEnvelope.failure(
                    "GENERATION_FAILED",
                    t.message ?: "image generation failed",
                )
            }

            val paths = results.mapNotNull { store(it) }
            if (paths.isEmpty()) {
                return@Function ToolEnvelope.failure(
                    "GENERATION_FAILED",
                    "the provider returned no usable image",
                )
            }
            ToolEnvelope.success {
                put("model", JsonPrimitive(spec.displayName))
                put("paths", JsonArray(paths.map(::JsonPrimitive)))
            }
        },
        // Not on the keyboard: a generated picture has nowhere to go in a text field.
        surfaces = setOf(Surface.CHAT, Surface.VOICE),
        maxCallsPerTurn = 2,
        errorCodes = setOf("INVALID_ARGS", "PROVIDER_UNAVAILABLE", "GENERATION_FAILED"),
        // Two identical prompts should produce two pictures, not one cached envelope.
        promptDoc = "Returns { paths: [...] } — filesystem paths to the generated images, already " +
            "attached to the conversation.",
    )

    /**
     * The user's pick for [Modality.Image], falling back to the catalog default. Waits for the connections
     * to be read, so a cold start never draws with the default in place of the user's pick.
     */
    private suspend fun resolveModel(): ImageModelSpec? =
        selection.activeModelFor(Modality.Image).first()
            ?.let { catalog.awaitById(it) }
            ?: catalog.awaitDefault()

    /**
     * A URL result is left as a URL: fetching it would mean a second HTTP client here and a download
     * policy this tool has no business owning. Bytes — what gpt-image-1 always returns — become a stored
     * attachment, the same path an imported photo takes.
     */
    private suspend fun store(result: ImageResult): String? = when (result) {
        is ImageResult.Bytes ->
            attachments.import(result.bytes, "generated-image.${result.format}")
        is ImageResult.Url -> result.url
    }

    private companion object {
        const val TAG = "AideTools"
    }
}
