package com.swaptr.aide.domain.tools.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.swaptr.aide.domain.llm.AideTool
import com.swaptr.aide.domain.llm.Surface
import com.swaptr.aide.domain.llm.ToolEnvelope
import com.swaptr.aide.domain.llm.ToolResult
import com.swaptr.aide.domain.tools.objectSchema
import com.swaptr.aide.domain.tools.stringProp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ClipboardToolset"
private val BOTH_SURFACES: Set<Surface> = setOf(Surface.CHAT, Surface.IME, Surface.VOICE)

private val CLIPBOARD_ERROR_CODES = setOf(
    "INVALID_ARGS", "CLIPBOARD_RESTRICTED", "IO_ERROR",
)

sealed class ClipboardResult : ToolResult {

    data class Got(
        val text: String?,
        val hasImage: Boolean,
        val hasUri: Boolean,
        val mime: String,
    ) : ClipboardResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.success {
            if (text != null) put("text", JsonPrimitive(text)) else put("text", kotlinx.serialization.json.JsonNull)
            put("has_image", JsonPrimitive(hasImage))
            put("has_uri", JsonPrimitive(hasUri))
            put("mime", JsonPrimitive(mime))
        }
    }

    data class Set(val length: Int) : ClipboardResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.success {
            put("length", JsonPrimitive(length))
        }
    }

    data class Err(val code: String, val message: String) : ClipboardResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.failure(code, message)
    }
}

// Android 13+ exposes description.extras.IS_SENSITIVE — we never return that text;
// the model gets null plus a flag.
@Singleton
class ClipboardToolset @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val clipboard: ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun asAideTools(): List<AideTool> = listOf(getTool(), setTool())

    private fun getTool(): AideTool = AideTool.Function(
        name = "ClipboardGet",
        description = "Return the current contents of the system clipboard as text. " +
            "If the clip is an image or URI, `text` is null and the appropriate flag is " +
            "set. From the chat surface this only works if Aide is in the foreground; " +
            "from the IME it always works because the keyboard is the focused IME.",
        parametersSchema = objectSchema(requiredProps = emptyList()),
        handler = {
            Log.i(TAG, "ClipboardGet called")
            readClip()
        },
        surfaces = BOTH_SURFACES,
        errorCodes = CLIPBOARD_ERROR_CODES,
    )

    private fun setTool(): AideTool = AideTool.Function(
        name = "ClipboardSet",
        description = "Replace the system clipboard's primary clip with the given text. " +
            "Returns the length of the text on success.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "text" to stringProp("Plain-text content to place on the clipboard."),
            ),
        ),
        handler = { args ->
            val text = args["text"]?.jsonPrimitive?.content
                ?: return@Function ClipboardResult.Err("INVALID_ARGS", "text required").toEnvelope()
            Log.i(TAG, "ClipboardSet called: ${text.length} chars")
            runCatching {
                clipboard.setPrimaryClip(ClipData.newPlainText("Aide", text))
            }.fold(
                onSuccess = { ClipboardResult.Set(text.length).toEnvelope() },
                onFailure = {
                    Log.w(TAG, "ClipboardSet failed", it)
                    val code = if (it is SecurityException) "CLIPBOARD_RESTRICTED" else "IO_ERROR"
                    ClipboardResult.Err(code, it.message ?: it.javaClass.simpleName).toEnvelope()
                },
            )
        },
        surfaces = BOTH_SURFACES,
        errorCodes = CLIPBOARD_ERROR_CODES,
    )

    private fun readClip(): JsonObject {
        val clip = runCatching { clipboard.primaryClip }
            .getOrElse {
                Log.w(TAG, "primaryClip read threw", it)
                return ClipboardResult.Err(
                    "CLIPBOARD_RESTRICTED",
                    "ensure Aide is focused (chat) or the Aide keyboard is active (IME)",
                ).toEnvelope()
            }
        if (clip == null || clip.itemCount == 0) {
            return ClipboardResult.Got(
                text = null, hasImage = false, hasUri = false, mime = "",
            ).toEnvelope()
        }
        val item = clip.getItemAt(0)
        val description = clip.description
        val mime = if (description.mimeTypeCount > 0) description.getMimeType(0) else ""
        val sensitive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            description.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true
        val text: String? = when {
            sensitive -> null
            else -> item.text?.toString()
        }
        return ClipboardResult.Got(
            text = text,
            hasImage = item.uri != null && mime.startsWith("image/"),
            hasUri = item.uri != null,
            mime = mime,
        ).toEnvelope()
    }
}
