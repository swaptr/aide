package com.sabreware.aide.aisdk.providers.bedrock

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.anthropic.ANTHROPIC_PROVIDER_ID
import com.sabreware.aide.aisdk.providers.anthropic.ANTHROPIC_SIGNATURE_KEY
import com.sabreware.aide.aisdk.util.AwsCredentials
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.content.OutgoingContent
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Bedrock is a transport, not a model family.
 *
 * The body is Anthropic's own, so the value here is in the three things that DO differ — SigV4 auth, the
 * model id moving to the URL, and the binary event stream — plus the one thing that must NOT differ:
 * signature handling, which is shared with the direct Anthropic provider rather than reimplemented.
 */
@OptIn(ExperimentalEncodingApi::class)
class BedrockTest {

    private var lastRequest: HttpRequestData? = null
    private val signature = "ErUBCkYIBRgCIkDXm2n4Q1p9sT7yZ0aVbNc"

    /** Wraps an Anthropic event in the JSON envelope and the AWS binary frame Bedrock actually sends. */
    private fun frame(anthropicEventJson: String): ByteArray {
        val envelope = """{"bytes":"${Base64.encode(anthropicEventJson.encodeToByteArray())}"}"""
        val payload = envelope.encodeToByteArray()
        val headers = listOf(":event-type" to "chunk", ":message-type" to "event")
        var headerBytes = ByteArray(0)
        headers.forEach { (k, v) ->
            headerBytes += byteArrayOf(k.length.toByte()) + k.encodeToByteArray() + byteArrayOf(7) +
                byteArrayOf((v.length shr 8).toByte(), v.length.toByte()) + v.encodeToByteArray()
        }
        val total = PRELUDE + headerBytes.size + payload.size + 4
        val prelude = int(total) + int(headerBytes.size)
        val message = prelude + int(crc32(prelude)) + headerBytes + payload
        return message + int(crc32(message))
    }

    private fun int(v: Int) =
        byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun crc32(bytes: ByteArray): Int {
        val table = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1) }
            c
        }
        var crc = -1
        bytes.forEach { crc = table[(crc xor it.toInt()) and 0xFF] xor (crc ushr 8) }
        return crc.inv()
    }

    private fun model(
        frames: ByteArray,
        modelId: String = "anthropic.claude-opus-4-5",
        region: String = "us-east-1",
        baseUrl: String = bedrockBaseUrl(region),
    ) = BedrockLanguageModel(
        modelId = modelId,
        http = ProviderHttp(
            HttpClient(
                MockEngine { request ->
                    lastRequest = request
                    respond(content = frames)
                },
            ),
        ),
        credentials = { AwsCredentials("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY") },
        region = region,
        now = { 1_705_314_645_000L },
        baseUrl = baseUrl,
    )

    private val call = CallOptions(
        prompt = listOf(ModelMessage.User(listOf(UserPart.Text("hi")))),
        reasoning = ReasoningEffort.Medium,
    )

    // Ktor wraps a ByteArray body in a nested OutgoingContent.ByteArrayContent, not the top-level type.
    private fun sentBody() =
        parseJsonObject((lastRequest!!.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())

    private val endTurn = """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}"""

    @Test
    fun `the model id goes in the URL and anthropic_version replaces it in the body`() = runTest {
        assembleGenerateResult(model(frame(endTurn)).doStream(call).stream)

        assertTrue(
            lastRequest!!.url.toString()
                .endsWith("/model/anthropic.claude-opus-4-5/invoke-with-response-stream"),
            lastRequest!!.url.toString(),
        )
        // Bedrock rejects a body that also names a model.
        assertNull(sentBody()["model"])
        assertEquals("bedrock-2023-05-31", sentBody()["anthropic_version"]?.jsonPrimitive?.content)
    }

    @Test
    fun `requests are SigV4 signed for the bedrock service`() = runTest {
        assembleGenerateResult(model(frame(endTurn)).doStream(call).stream)

        val auth = lastRequest!!.headers["Authorization"]!!
        assertTrue(auth.startsWith("AWS4-HMAC-SHA256 "), auth)
        assertTrue(auth.contains("/20240115/us-east-1/bedrock/aws4_request"), auth)
        assertEquals("20240115T103045Z", lastRequest!!.headers["x-amz-date"])
    }

    @Test
    fun `the thinking config comes from the shared Anthropic rules, not a per-transport copy`() = runTest {
        assembleGenerateResult(model(frame(endTurn)).doStream(call).stream)

        // claude-opus-4-5 is extended-thinking-only; the model table is shared, not re-derived here.
        assertTrue(sentBody()["thinking"].toString().contains("\"enabled\""), sentBody().toString())
    }

    @Test
    fun `a signature survives the binary transport intact`() = runTest {
        val frames =
            frame("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""") +
                frame("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"weighing it"}}""") +
                frame("""{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"$signature"}}""") +
                frame("""{"type":"content_block_stop","index":0}""") +
                frame(endTurn)

        val result = assembleGenerateResult(model(frames).doStream(call).stream)

        // Same mapper as the direct Anthropic provider. A second copy would eventually drift, and the
        // one that drifted would fail as an opaque 400 on the next tool round.
        val reasoning = result.content.single() as Content.Reasoning
        assertEquals("weighing it", reasoning.text)
        assertEquals(
            signature,
            reasoning.providerMetadata?.get(ANTHROPIC_PROVIDER_ID)
                ?.get(ANTHROPIC_SIGNATURE_KEY)?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `several frames in one response all decode`() = runTest {
        val frames =
            frame("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""") +
                frame("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""") +
                frame(endTurn)

        val result = assembleGenerateResult(model(frames).doStream(call).stream)

        assertEquals("ok", (result.content.single() as Content.Text).text)
    }

    @Test
    fun `anthropic ids keep the native path and everything else routes to Converse`() {
        val provider = BedrockProvider(
            client = HttpClient(MockEngine { respond(content = ByteArray(0)) }),
            credentials = { AwsCredentials("a", "b") },
            region = "us-east-1",
            now = { 0L },
        )

        // The native path is where the shared Anthropic signature handling lives; Converse is
        // Bedrock's one wire for every other hosted vendor. Routing is by id, including the
        // cross-region inference profile prefix.
        assertTrue(provider.languageModel("anthropic.claude-opus-4-5") is BedrockLanguageModel)
        assertTrue(provider.languageModel("us.anthropic.claude-opus-4-5") is BedrockLanguageModel)
        assertTrue(provider.languageModel("meta.llama3-70b") is BedrockConverseLanguageModel)
        assertTrue(provider.languageModel("us.amazon.nova-pro-v1:0") is BedrockConverseLanguageModel)
    }

    private companion object {
        const val PRELUDE = 12
    }

    @Test
    fun `bedrock upgrades old tool versions, renames the editor, and spells its betas`() = runTest {
        val options = call.copy(
            tools = listOf(
                com.sabreware.aide.aisdk.Tool.ProviderDefined(
                    name = "bash", id = "anthropic.bash_20241022",
                    args = kotlinx.serialization.json.JsonObject(emptyMap()),
                ),
                com.sabreware.aide.aisdk.Tool.ProviderDefined(
                    name = "str_replace_editor", id = "anthropic.text_editor_20241022",
                    args = kotlinx.serialization.json.JsonObject(emptyMap()),
                ),
                com.sabreware.aide.aisdk.Tool.Function("f", kotlinx.serialization.json.JsonObject(emptyMap())),
            ),
        )
        assembleGenerateResult(model(frame(endTurn)).doStream(options).stream)

        val tools = sentBody()["tools"]!!.let { it as kotlinx.serialization.json.JsonArray }
        val types = tools.map { it.jsonObject["type"]?.jsonPrimitive?.content }
        // The direct API's default versions are a rejection here; Bedrock wants the newer ones.
        assertTrue("bash_20250124" in types, types.toString())
        assertTrue("text_editor_20250728" in types, types.toString())
        val editor = tools.first { it.jsonObject["type"]?.jsonPrimitive?.content == "text_editor_20250728" }
        assertEquals("str_replace_based_edit_tool", editor.jsonObject["name"]!!.jsonPrimitive.content)

        // The function tool's per-tool eager streaming field becomes a beta on this host.
        val function = tools.first { it.jsonObject["type"] == null }
        assertTrue("eager_input_streaming" !in function.jsonObject, function.toString())
        val betas = sentBody()["anthropic_beta"]!!.let { it as kotlinx.serialization.json.JsonArray }
            .map { it.jsonPrimitive.content }
        assertTrue("fine-grained-tool-streaming-2025-05-14" in betas, betas.toString())
        assertTrue("computer-use-2025-01-24" in betas, betas.toString())
    }

    @Test
    fun `bedrock's tool_choice keeps only type and name`() = runTest {
        // No thinking: extended thinking would drop the forced choice to auto before the remap runs.
        val options = call.copy(
            reasoning = ReasoningEffort.None,
            tools = listOf(com.sabreware.aide.aisdk.Tool.Function("f", kotlinx.serialization.json.JsonObject(emptyMap()))),
            toolChoice = com.sabreware.aide.aisdk.ToolChoice.Required,
            providerOptions = mapOf(
                "anthropic" to kotlinx.serialization.json.buildJsonObject {
                    put("disableParallelToolUse", kotlinx.serialization.json.JsonPrimitive(true))
                },
            ),
        )
        assembleGenerateResult(model(frame(endTurn)).doStream(options).stream)

        val choice = sentBody()["tool_choice"]!!.jsonObject
        assertEquals("any", choice["type"]!!.jsonPrimitive.content)
        // Bedrock rejects the field the direct API accepts.
        assertTrue("disable_parallel_tool_use" !in choice, choice.toString())
    }

    @Test
    fun `options under amazon-bedrock are read, merged over anthropic`() = runTest {
        val options = call.copy(
            providerOptions = mapOf(
                "amazon-bedrock" to kotlinx.serialization.json.buildJsonObject {
                    put("serviceTier", kotlinx.serialization.json.JsonPrimitive("priority"))
                },
            ),
        )
        assembleGenerateResult(model(frame(endTurn)).doStream(options).stream)

        assertEquals("priority", sentBody()["service_tier"]!!.jsonPrimitive.content)
    }

    // --- The 2026-09 upstream delta ------------------------------------------------------------

    @Test
    fun `the thinking binding control is renamed for Bedrock`() = runTest {
        // Reference `9a46ac6`: Bedrock spells it `mismatch_behavior`; some regions alias the Anthropic
        // spelling, others reject it, so the rename is unconditional.
        val options = call.copy(
            providerOptions = mapOf(
                ANTHROPIC_PROVIDER_ID to buildJsonObject {
                    putJsonObject("thinking") {
                        put("type", "adaptive")
                        put("display", "summarized")
                        putJsonObject("blockBinding") { put("prefixMismatchBehavior", "drop_block") }
                    }
                },
            ),
        )
        assembleGenerateResult(model(frame(endTurn), modelId = "anthropic.claude-fable-5").doStream(options).stream)

        assertEquals(
            parseJsonObject("""{"type":"adaptive","display":"summarized","block_binding":{"mismatch_behavior":"drop_block"}}"""),
            sentBody()["thinking"],
        )
        val betas = sentBody()["anthropic_beta"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("thinking-binding-controls-2026-08-01" in betas, betas.toString())
    }

    @Test
    fun `thinking without a binding is untouched`() = runTest {
        assembleGenerateResult(model(frame(endTurn)).doStream(call).stream)

        val thinking = sentBody()["thinking"]!!.jsonObject
        assertEquals("enabled", thinking["type"]!!.jsonPrimitive.content)
        assertNull(thinking["block_binding"])
    }

    private val jsonFormat = ResponseFormat.Json(
        schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("name") { put("type", "string") } }
            put("required", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("name"))))
        },
    )

    @Test
    fun `native structured output is off for the models Bedrock's schema rejects or serves unreliably`() = runTest {
        // Reference `bd74b49` and its test table: Bedrock rejects `output_config.format` for the newest
        // families and serves it unreliably on Sonnet 4.6 and Haiku 4.5, so all of them take the JSON tool.
        val ids = listOf(
            "anthropic.claude-haiku-4-5-20251001-v1:0", "us.anthropic.claude-haiku-4-5-20251001-v1:0",
            "eu.anthropic.claude-haiku-4-5-20251001-v1:0", "global.anthropic.claude-haiku-4-5-20251001-v1:0",
            "anthropic.claude-sonnet-4-6-v1", "us.anthropic.claude-sonnet-4-6-v1",
            "eu.anthropic.claude-sonnet-4-6-v1", "global.anthropic.claude-sonnet-4-6-v1",
            "anthropic.claude-opus-4-7", "us.anthropic.claude-opus-4-7", "eu.anthropic.claude-opus-4-7",
            "anthropic.claude-opus-4-8", "anthropic.claude-opus-5", "us.anthropic.claude-opus-5",
            "anthropic.claude-fable-5", "us.anthropic.claude-fable-5", "eu.anthropic.claude-fable-5",
            "anthropic.claude-fable-5-1", "us.anthropic.claude-fable-5-1", "global.anthropic.claude-fable-5-1",
            "anthropic.claude-sonnet-5", "us.anthropic.claude-sonnet-5", "eu.anthropic.claude-sonnet-5",
        )
        ids.forEach { id ->
            assembleGenerateResult(
                model(frame(endTurn), modelId = id).doStream(call.copy(reasoning = ReasoningEffort.None, responseFormat = jsonFormat)).stream,
            )
            val body = sentBody()
            assertNull(body["output_config"]?.jsonObject?.get("format"), "$id: $body")
            val tools = body["tools"]!!.jsonArray
            assertEquals("json", tools.single().jsonObject["name"]!!.jsonPrimitive.content, id)
            assertEquals("json", body["tool_choice"]!!.jsonObject["name"]!!.jsonPrimitive.content, id)
        }
    }

    @Test
    fun `a model Bedrock serves structured output for keeps the native format`() = runTest {
        assembleGenerateResult(
            model(frame(endTurn), modelId = "anthropic.claude-sonnet-4-5-20250929-v1:0")
                .doStream(call.copy(reasoning = ReasoningEffort.None, responseFormat = jsonFormat)).stream,
        )

        val body = sentBody()
        assertEquals("json_schema", body["output_config"]!!.jsonObject["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(body["tools"])
    }

    @Test
    fun `the endpoint follows the region's partition`() = runTest {
        // Reference `5191b61`: the commercial suffix does not resolve from the other partitions.
        listOf(
            "cn-north-1" to "amazonaws.com.cn",
            "us-gov-west-1" to "amazonaws.com",
            "us-iso-east-1" to "c2s.ic.gov",
            "us-isob-east-1" to "sc2s.sgov.gov",
            "eu-isoe-west-1" to "cloud.adc-e.uk",
            "us-isof-south-1" to "csp.hci.ic.gov",
            "eusc-de-east-1" to "amazonaws.eu",
        ).forEach { (region, suffix) ->
            assertEquals("https://bedrock-runtime.$region.$suffix", bedrockBaseUrl(region), region)
            assertEquals(
                "https://bedrock-agent-runtime.$region.$suffix",
                bedrockBaseUrl(region, BEDROCK_AGENT_RUNTIME_SERVICE),
                region,
            )
        }
        assertEquals("https://explicit.example.com", bedrockBaseUrl("us-east-1", override = "https://explicit.example.com/"))

        assembleGenerateResult(model(frame(endTurn), region = "cn-north-1").doStream(call).stream)
        assertTrue(
            lastRequest!!.url.toString().startsWith("https://bedrock-runtime.cn-north-1.amazonaws.com.cn/model/"),
            lastRequest!!.url.toString(),
        )
        // The signing scope still names the region, whatever the host.
        assertTrue(lastRequest!!.headers["Authorization"]!!.contains("/cn-north-1/bedrock/aws4_request"))
    }

    @Test
    fun `a declared anthropic family routes an ARN to Converse with the family attached`() {
        val provider = BedrockProvider(
            client = HttpClient(MockEngine { respond(content = ByteArray(0)) }),
            credentials = { AwsCredentials("a", "b") },
            region = "us-east-1",
            now = { 0L },
        )
        val arn = "arn:aws:bedrock:us-east-1:123456789012:application-inference-profile/qibm5eutlkcy"

        assertTrue(provider.languageModel(arn, modelFamily = BEDROCK_MODEL_FAMILY_ANTHROPIC) is BedrockConverseLanguageModel)
        assertTrue(provider.languageModel("anthropic.claude-opus-4-5", modelFamily = BEDROCK_MODEL_FAMILY_ANTHROPIC) is BedrockLanguageModel)
    }
}
