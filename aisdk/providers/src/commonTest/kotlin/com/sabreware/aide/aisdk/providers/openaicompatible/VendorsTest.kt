package com.sabreware.aide.aisdk.providers.openaicompatible

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.EmbeddingCallOptions
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.UserPart
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import com.sabreware.aide.aisdk.providers.groq.GroqProvider
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The vendor table.
 *
 * These are one-line factories, so the tests are about the things that are silently wrong rather than
 * loudly wrong: a URL that 404s, a key sent where it should not be, a `stream_options` that a local
 * server rejects outright.
 */
class VendorsTest {

    private var lastRequest: HttpRequestData? = null

    private fun client(): HttpClient = HttpClient(
        MockEngine { request ->
            lastRequest = request
            respond(
                content = "data: {\"id\":\"c\",\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n",
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        },
    )

    /** A client that answers JSON, for the non-chat endpoints. */
    private fun jsonClient(body: String = "{}"): HttpClient = HttpClient(
        MockEngine { request ->
            lastRequest = request
            respond(content = body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )

    private val call = CallOptions(prompt = listOf(ModelMessage.User(listOf(UserPart.Text("hi")))))

    private suspend fun embed(provider: OpenAICompatibleProvider, modelId: String) {
        provider.embeddingModel(modelId)!!.doEmbed(EmbeddingCallOptions(values = listOf("x")))
    }

    private suspend fun run(provider: OpenAICompatibleProvider, modelId: String = "m") {
        provider.languageModel(modelId)!!.doStream(call).stream.toList()
    }

    @Test
    fun `hosted vendors send a bearer token to their documented endpoint`() = runTest {
        val cases = listOf<Pair<OpenAICompatibleProvider, String>>(
            Vendors.openAI(client(), "k") to "https://api.openai.com/v1/chat/completions",
            Vendors.groq(client(), "k") to "https://api.groq.com/openai/v1/chat/completions",
            Vendors.openRouter(client(), "k") to "https://openrouter.ai/api/v1/chat/completions",
            Vendors.xai(client(), "k") to "https://api.x.ai/v1/chat/completions",
            Vendors.together(client(), "k") to "https://api.together.xyz/v1/chat/completions",
            Vendors.cerebras(client(), "k") to "https://api.cerebras.ai/v1/chat/completions",
        )

        cases.forEach { (provider, url) ->
            run(provider)
            assertEquals(url, lastRequest!!.url.toString(), "wrong endpoint")
            assertEquals("Bearer k", lastRequest!!.headers[HttpHeaders.Authorization])
        }
    }

    @Test
    fun `local runtimes send no Authorization header at all`() = runTest {
        listOf(
            Vendors.ollama(client()),
            Vendors.lmStudio(client()),
            Vendors.llamaCpp(client()),
            Vendors.vllm(client(), "http://localhost:8000/v1"),
        ).forEach { provider ->
            run(provider)
            // An empty bearer is how a working keyless setup starts returning 401.
            assertNull(lastRequest!!.headers[HttpHeaders.Authorization])
        }
    }

    @Test
    fun `the local runtimes that document usage support now ask for it`() = runTest {
        // Ollama's and llama.cpp's own OpenAI-compatibility pages list `stream_options.include_usage` as
        // implemented (checked 2026-09-01). This used to be off on the premise that builds of the day
        // rejected the field, and the price of that caution was a null token count on every streamed
        // turn — a cost paid on every call to avoid a failure that no longer happens.
        for (provider in listOf(Vendors.ollama(client()), Vendors.llamaCpp(client()))) {
            run(provider)
            val body = parseJsonObject((lastRequest!!.body as TextContent).text)
            assertEquals(
                true,
                body.obj("stream_options")?.get("include_usage")?.let { it.toString() == "true" },
            )
        }
    }

    @Test
    fun `LM Studio still omits it, because its docs do not say either way`() = runTest {
        run(Vendors.lmStudio(client()))

        // The distinction is deliberate: an unverified flag that can fail a request is not worth a token
        // count, and LM Studio's compatibility page documents the endpoints without addressing this one.
        val body = parseJsonObject((lastRequest!!.body as TextContent).text)
        assertNull(body["stream_options"])
    }

    @Test
    fun `hosted vendors ask for usage`() = runTest {
        run(Vendors.openAI(client(), "k"))

        val body = parseJsonObject((lastRequest!!.body as TextContent).text)
        assertNotNull(body["stream_options"], "without this a streamed response reports no tokens at all")
    }

    @Test
    fun `groq omits stream_options and reads its counts off x_groq`() = runTest {
        val groq = Vendors.groq(client(), "k")

        run(groq)

        // Groq does not implement stream_options: it reports usage on `x_groq.usage` and nowhere else,
        // so asking for a usage chunk that never comes was how streamed usage stayed null on every call.
        assertNull(parseJsonObject((lastRequest!!.body as TextContent).text)["stream_options"])
    }

    @Test
    fun `azure uses api-key rather than a bearer token`() = runTest {
        // The DEFAULT language model is the Responses API (pinned in OpenAIResponsesEndpointsTest);
        // this is the Chat Completions deployment path, kept reachable beside it.
        Vendors.azure(client(), "k", resourceName = "my-resource")
            .chatLanguageModel("my-deployment")!!.doStream(call).stream.toList()

        // Bearer would 401 here, and the model id is a deployment name, not a model name.
        assertNull(lastRequest!!.headers[HttpHeaders.Authorization])
        assertEquals("k", lastRequest!!.headers["api-key"])
        assertEquals(
            "https://my-resource.openai.azure.com/openai/deployments/my-deployment" +
                "/chat/completions?api-version=2024-10-21",
            lastRequest!!.url.toString(),
        )
    }

    @Test
    fun `azure puts the deployment and api-version into every modality, not only chat`() = runTest {
        // A chat-only URL override left these resolving to `.../openai/deployments/embeddings`: no
        // deployment segment, no api-version, and a 404 from a provider that says it does embeddings.
        embed(Vendors.azure(jsonClient("""{"data":[]}"""), "k", resourceName = "my-resource"), "embed-3")

        assertEquals(
            "https://my-resource.openai.azure.com/openai/deployments/embed-3/embeddings" +
                "?api-version=2024-10-21",
            lastRequest!!.url.toString(),
        )
    }

    @Test
    fun `exactly the five rows whose reference serves it carry the legacy Completion modality`() {
        // Verified against the reference's provider files (`grep -rn completion packages/*/src/*provider.ts`):
        // openai, azure, fireworks, togetherai and deepinfra expose a completion model; nobody else does.
        // A row that claimed it would hand a caller a model that 404s on first use.
        assertNotNull(Vendors.openAI(client(), "k").completionModel("gpt-3.5-turbo-instruct"))
        assertNotNull(Vendors.azure(client(), "k", resourceName = "r").completionModel("d"))
        assertNotNull(Vendors.fireworks(client(), "k").completionModel("m"))
        assertNotNull(Vendors.together(client(), "k").completionModel("m"))
        assertNotNull(Vendors.deepInfra(client(), "k").completionModel("m"))

        listOf(
            Vendors.groq(client(), "k"),
            Vendors.openRouter(client(), "k"),
            Vendors.vercelGateway(client(), "k"),
            Vendors.cerebras(client(), "k"),
            Vendors.xai(client(), "k"),
            Vendors.huggingFace(client(), "k"),
            Vendors.byteDance(client(), "k"),
            Vendors.gmiCloud(client(), "k"),
            Vendors.baseten(client(), "k"),
            Vendors.ollama(client()),
            Vendors.lmStudio(client()),
            Vendors.llamaCpp(client()),
            Vendors.vllm(client(), "http://localhost:8000/v1"),
            Vendors.custom(client(), providerId = "x", baseUrl = "https://x.invalid/v1"),
        ).forEach { provider ->
            assertNull(provider.completionModel("m"), "${provider.providerId} must not advertise completions")
        }
    }

    @Test
    fun `a vendor offers only the endpoints it actually serves`() {
        // The ratchet: a capability a vendor lacks is bound NOWHERE. Returning a model that 404s makes
        // "does this provider do images?" unanswerable without calling it and catching.
        // Groq's /audio/transcriptions is near OpenAI's and not it, so it has its own wire. Binding it
        // here as well would give one vendor two implementations of one modality, and the caller has no
        // way to tell which one it got.
        val groq = Vendors.groq(client(), "k")
        assertNull(groq.transcriptionModel("whisper-large-v3"))
        assertNull(groq.imageModel("anything"))
        assertNull(groq.embeddingModel("anything"))
        val native = GroqProvider(client = client(), apiKey = "k")
        assertNotNull(native.transcriptionModel("whisper-large-v3"))

        // xAI runs speech at /tts and transcription at /stt with its own request shapes, so the shared
        // OpenAI-shaped models would compile, type-check and fail against the live service.
        val xai = Vendors.xai(client(), "k")
        assertNotNull(xai.imageModel("grok-2-image"))

        // Fireworks' image API is its own `workflows/{model}` wire and DeepInfra's is
        // `/inference/{modelId}` — the shared /images/generations model 404s on both, so neither
        // advertises Image (the native ports are tracked in TODO.md).
        assertNull(Vendors.fireworks(client(), "k").imageModel("flux-1"))
        assertNull(Vendors.deepInfra(client(), "k").imageModel("sdxl"))
        assertNull(xai.speechModel("anything"))
        assertNull(xai.transcriptionModel("anything"))

        val cerebras = Vendors.cerebras(client(), "k")
        assertNull(cerebras.imageModel("anything"))
        assertNull(cerebras.embeddingModel("anything"))
        assertNull(cerebras.speechModel("anything"))
        assertNull(cerebras.transcriptionModel("anything"))
    }

    @Test
    fun `openrouter attribution headers are sent only when supplied`() = runTest {
        run(Vendors.openRouter(client(), "k", appUrl = "https://aide.app", appTitle = "AIDE"))
        assertEquals("https://aide.app", lastRequest!!.headers["HTTP-Referer"])
        assertEquals("AIDE", lastRequest!!.headers["X-Title"])

        run(Vendors.openRouter(client(), "k"))
        assertNull(lastRequest!!.headers["HTTP-Referer"])
    }

    @Test
    fun `each vendor files its metadata under its own id`() {
        // Not cosmetic: reasoning blocks replay under the id that produced them.
        assertEquals("openrouter", Vendors.openRouter(client(), "k").providerId)
        assertEquals("ollama", Vendors.ollama(client()).providerId)
        assertEquals("groq", Vendors.groq(client(), "k").providerId)
    }

    @Test
    fun `vercel gateway rides its OpenAI-compatible endpoint`() = runTest {
        run(Vendors.vercelGateway(client(), "k"), modelId = "anthropic/claude-sonnet-4")

        // Gateway's NATIVE protocol serializes the SDK's own types in a private shape; the compatible
        // endpoint is the one that is documented and testable. See aisdk/DESIGN.md.
        assertEquals("https://ai-gateway.vercel.sh/v1/chat/completions", lastRequest!!.url.toString())
        assertEquals("Bearer k", lastRequest!!.headers[HttpHeaders.Authorization])
        val body = parseJsonObject((lastRequest!!.body as TextContent).text)
        // Model ids are creator/model here, and must pass through untouched.
        assertEquals("anthropic/claude-sonnet-4", body["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `custom covers a vendor the table has never heard of`() = runTest {
        val provider = Vendors.custom(
            client = client(),
            providerId = "someone-new",
            baseUrl = "https://new.invalid/v1",
            apiKey = "k",
        )

        run(provider)

        assertEquals("https://new.invalid/v1/chat/completions", lastRequest!!.url.toString())
        assertEquals("someone-new", provider.providerId)
    }

    // --- ai@7.0.102: Azure base URLs (993e900, 85db433) -------------------------------------------

    private fun sentBody() = parseJsonObject((lastRequest!!.body as TextContent).text)

    @Test
    fun `an Azure base URL is spelled the way its host reads it, gateway to Foundry project`() = runTest {
        // The reference's table (`azure-openai-provider.test.ts`): an unversioned Azure host gets the v1
        // form and `api-version=v1`; a complete `/openai/v1` base and a Foundry project own their
        // versioning; a gateway owns its routing outright.
        val cases = listOf(
            "https://test-resource.openai.azure.com/openai/v1" to
                "https://test-resource.openai.azure.com/openai/v1/chat/completions",
            "https://test-resource.services.ai.azure.com/openai" to
                "https://test-resource.services.ai.azure.com/openai/v1/chat/completions?api-version=v1",
            "https://test-resource.services.ai.azure.com/openai/v1/" to
                "https://test-resource.services.ai.azure.com/openai/v1/chat/completions",
            "https://test-resource.cognitiveservices.azure.com/openai" to
                "https://test-resource.cognitiveservices.azure.com/openai/v1/chat/completions?api-version=v1",
            "https://test-resource.cognitiveservices.azure.com/openai/v1" to
                "https://test-resource.cognitiveservices.azure.com/openai/v1/chat/completions",
            "https://test-resource.services.ai.azure.com/api/projects/test-project/openai" to
                "https://test-resource.services.ai.azure.com/api/projects/test-project/openai/v1/chat/completions",
            "https://test-resource.services.ai.azure.com/api/projects/test-project/openai/v1" to
                "https://test-resource.services.ai.azure.com/api/projects/test-project/openai/v1/chat/completions",
            "https://gateway.example.com/azure" to "https://gateway.example.com/azure/chat/completions",
        )
        for ((baseUrl, expected) in cases) {
            Vendors.azure(client(), "k", baseUrl = baseUrl)
                .chatLanguageModel("my-deployment")!!.doStream(call).stream.toList()

            assertEquals(expected, lastRequest!!.url.toString(), baseUrl)
            // The v1 form takes the deployment as the body's model, never as a path segment.
            assertEquals("my-deployment", sentBody()["model"].string(), baseUrl)
            assertEquals("k", lastRequest!!.headers["api-key"], baseUrl)
        }
    }

    @Test
    fun `an Azure base URL spells every modality, and a caller's api-version is the one appended`() = runTest {
        embed(
            Vendors.azure(jsonClient("""{"data":[]}"""), "k", baseUrl = "https://r.services.ai.azure.com/openai", apiVersion = "preview"),
            "embed-3",
        )

        assertEquals(
            "https://r.services.ai.azure.com/openai/v1/embeddings?api-version=preview",
            lastRequest!!.url.toString(),
        )
    }

    @Test
    fun `an Azure base URL serves the Responses API there too, and a Foundry project wants explicit item types`() = runTest {
        val foundry = "https://test-resource.services.ai.azure.com/api/projects/test-project/openai/v1"
        val model = Vendors.azure(jsonClient("""{"id":"resp_1","output":[]}"""), "k", baseUrl = foundry)
            .languageModel("my-deployment")!!

        model.doGenerate(call)

        // Versioned by the project path, so no `api-version` — matching the reference's own fixture.
        assertEquals("$foundry/responses", lastRequest!!.url.toString())
        assertEquals("my-deployment", sentBody()["model"].string())
        assertEquals("k", lastRequest!!.headers["api-key"])
        assertEquals("azure.responses", model.provider)
        // The fact the Responses request has to honour with `type: "message"` on every input item; the
        // model's quirk for it is the OpenAI lane's, so only the rule is pinned here.
        assertTrue(azureBaseUrlInfo(foundry).explicitMessageItemType)
        assertFalse(azureBaseUrlInfo("https://test-resource.openai.azure.com/openai/v1").explicitMessageItemType)
    }

    @Test
    fun `azure still needs one of resourceName and baseUrl, and baseUrl wins`() = runTest {
        assertFailsWith<IllegalArgumentException> { Vendors.azure(client(), "k") }

        Vendors.azure(client(), "k", resourceName = "ignored", baseUrl = "https://gateway.example.com/azure")
            .chatLanguageModel("d")!!.doStream(call).stream.toList()
        assertEquals("https://gateway.example.com/azure/chat/completions", lastRequest!!.url.toString())
    }

    @Test
    fun `the Azure host rule reads the three Azure hosts and nothing else`() {
        val foundryProject = azureBaseUrlInfo("https://r.services.ai.azure.com/api/projects/p/openai")
        assertTrue(foundryProject.isAzureOpenAI && foundryProject.isFoundryProject && !foundryProject.isVersioned)
        val versioned = azureBaseUrlInfo("https://r.cognitiveservices.azure.com/openai/V1/")
        assertTrue(versioned.isAzureOpenAI && !versioned.isFoundryProject && versioned.isVersioned)
        val gateway = azureBaseUrlInfo("https://gateway.example.com/openai/v1")
        assertFalse(gateway.isAzureOpenAI || gateway.isFoundryProject || gateway.isVersioned)
        // `api.example.com.openai.azure.com.attacker.net` ends with the right string and is another site.
        assertFalse(azureBaseUrlInfo("https://r.openai.azure.com.attacker.net/openai").isAzureOpenAI)
    }

    // --- ai@7.0.102: the rules that hold where OpenAI itself serves (17e489e, 17d3436, d5e3024) -----

    private suspend fun openAIStream(modelId: String, options: CallOptions): List<StreamPart> =
        Vendors.openAI(client(), "k").languageModel(modelId)!!.doStream(options).stream.toList()

    private fun List<StreamPart>.startWarnings(): List<Warning> =
        filterIsInstance<StreamPart.StreamStart>().single().warnings

    private fun openai(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        mapOf("openai" to buildJsonObject(build))

    @Test
    fun `GPT-6 takes a closed effort list, and an effort off it is dropped with the list named`() = runTest {
        // The reference's fixture: `none` and `minimal` are gpt-5 vocabulary and 400 on gpt-6-astra.
        for (effort in listOf("none", "minimal")) {
            val parts = openAIStream("gpt-6-astra", call.copy(providerOptions = openai { put("reasoning_effort", effort) }))

            assertNull(sentBody()["reasoning_effort"], effort)
            parts.startWarnings().assertUnsupported(
                "reasoningEffort",
                "gpt-6-astra only supports the following reasoning efforts: low, medium, high, xhigh, max",
            )
        }

        // The neutral level the engine itself spells is judged by the same list.
        val neutral = openAIStream("gpt-6-astra", call.copy(reasoning = ReasoningEffort.Minimal))
        assertNull(sentBody()["reasoning_effort"])
        neutral.startWarnings().assertUnsupported("reasoningEffort")

        val kept = openAIStream("gpt-6-astra", call.copy(providerOptions = openai { put("reasoning_effort", "low") }))
        assertEquals("low", sentBody()["reasoning_effort"].string())
        assertTrue(kept.startWarnings().none { it is Warning.Unsupported && it.feature == "reasoningEffort" })

        // gpt-5.6 still takes the open vocabulary.
        openAIStream("gpt-5.6-sol", call.copy(providerOptions = openai { put("reasoning_effort", "none") }))
        assertEquals("none", sentBody()["reasoning_effort"].string())
    }

    @Test
    fun `GPT-6 retired prompt_cache_retention, so it is dropped with a pointer to its replacement`() = runTest {
        val parts = openAIStream("gpt-6-astra", call.copy(providerOptions = openai { put("prompt_cache_retention", "24h") }))

        assertNull(sentBody()["prompt_cache_retention"])
        parts.startWarnings().assertUnsupported(
            "promptCacheRetention",
            "promptCacheRetention is not supported by GPT-6 and later models; use promptCacheOptions instead",
        )

        openAIStream("gpt-5.6-sol", call.copy(providerOptions = openai { put("prompt_cache_retention", "24h") }))
        assertEquals("24h", sentBody()["prompt_cache_retention"].string())
    }

    @Test
    fun `the ultrafast service tier reaches the wire as spelled`() = runTest {
        openAIStream("gpt-5.6-sol", call.copy(providerOptions = openai { put("service_tier", "ultrafast") }))

        assertEquals("ultrafast", sentBody()["service_tier"].string())
    }

    @Test
    fun `OpenAI strips propertyNames from tool and response schemas and says so, other rows leave it alone`() = runTest {
        // The reference's fixture (`openai-chat-prepare-tools.test.ts`).
        val schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("values") {
                    put("type", "object")
                    putJsonObject("propertyNames") {
                        put("type", "string")
                        put("pattern", "^[A-Z_]+$")
                    }
                }
            }
        }
        val stripped = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("values") { put("type", "object") } }
        }
        val options = call.copy(
            tools = listOf(Tool.Function(name = "testFunction", inputSchema = schema)),
            responseFormat = ResponseFormat.Json(schema = schema),
        )

        val parts = openAIStream("gpt-4o", options)

        val body = sentBody()
        assertEquals(stripped, body.getValue("tools").jsonArray.single().jsonObject.obj("function", "parameters"))
        assertEquals(stripped, body.obj("response_format", "json_schema", "schema"))
        val compatibility = parts.startWarnings().filterIsInstance<Warning.Compatibility>()
        assertEquals(listOf("JSON Schema propertyNames", "JSON Schema propertyNames"), compatibility.map { it.feature })
        assertEquals(
            "OpenAI does not support JSON Schema propertyNames. It was removed before sending the schema, " +
                "so OpenAI will not enforce property-name constraints.",
            compatibility.first().details,
        )

        // A self-hosted server that validates the keyword keeps it: the rule is OpenAI's, not the wire's.
        OpenAICompatibleProvider(client(), providerId = "local", baseUrl = "https://local/v1")
            .languageModel("m")!!.doStream(options).stream.toList()
        assertEquals(schema, sentBody().getValue("tools").jsonArray.single().jsonObject.obj("function", "parameters"))
    }
}
