package com.sabreware.aide.aisdk.providers.cohere

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.EmbeddingCallOptions
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.RerankingCallOptions
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.TooManyEmbeddingValuesForCallError
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.testing.TestServer
import io.ktor.client.HttpClient
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.assertCompatibility
import com.sabreware.aide.aisdk.providers.testing.assertNoWarningAbout
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.int
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Cohere v2, which has its own wire rather than an OpenAI-compatible one.
 *
 * The streaming fixtures are the reference's own recordings (`cohere-reasoning.chunks.txt`,
 * `cohere-tool-call.chunks.txt`) and the response bodies its recorded JSON — so agreeing with them is a
 * check on our reading of the protocol rather than on our own idea of it.
 */
class CohereTest {

    private fun sse(vararg lines: String) =
        TestServer.sse(lines.joinToString("") { "data: $it\n\n" })

    private fun model(server: TestServer) = CohereLanguageModel(
        modelId = "command-a-03-2025",
        http = server.http(),
        baseUrl = "https://api.cohere.com/v2",
        headers = mapOf("Authorization" to "Bearer k"),
    )

    private val call = CallOptions(prompt = listOf(ModelMessage.User(listOf(UserPart.Text("hi")))))

    private val done = """{"type":"message-end","delta":{"finish_reason":"COMPLETE"}}"""

    // ---- The thinking channel --------------------------------------------------------------------

    @Test
    fun `thinking content opens a reasoning block, not the answer's text block`() = runTest {
        // The reference's recorded command-a-reasoning wire: index 0 is thinking, index 1 the answer,
        // and `content-delta` says which is which only by the key it carries.
        val server = TestServer(
            sse(
                """{"id":"c9117d7f","type":"message-start","delta":{"message":{"role":"assistant"}}}""",
                """{"type":"content-start","index":0,"delta":{"message":{"content":{"type":"thinking","thinking":""}}}}""",
                """{"type":"content-delta","index":0,"delta":{"message":{"content":{"thinking":"The"}}}}""",
                """{"type":"content-delta","index":0,"delta":{"message":{"content":{"thinking":" answer"}}}}""",
                """{"type":"content-end","index":0}""",
                """{"type":"content-start","index":1,"delta":{"message":{"content":{"type":"text","text":""}}}}""",
                """{"type":"content-delta","index":1,"delta":{"message":{"content":{"text":"4."}}}}""",
                """{"type":"content-end","index":1}""",
                done,
            ),
        )

        val parts = model(server).doStream(call).stream.toList()

        // Before this, `content-start` opened a TEXT block whatever its type and `content-delta` read
        // only `.text` — so the model's private deliberation was dropped and the answer arrived alone.
        assertEquals(
            listOf("0" to "The", "0" to " answer"),
            parts.filterIsInstance<StreamPart.ReasoningDelta>().map { it.id to it.delta },
        )
        assertEquals(
            listOf("1" to "4."),
            parts.filterIsInstance<StreamPart.TextDelta>().map { it.id to it.delta },
        )
        assertEquals(listOf("0"), parts.filterIsInstance<StreamPart.ReasoningStart>().map { it.id })
        assertEquals(listOf("1"), parts.filterIsInstance<StreamPart.TextStart>().map { it.id })
    }

    @Test
    fun `tool_plan is the other reasoning channel and streams alongside the calls`() = runTest {
        val server = TestServer(
            sse(
                """{"type":"tool-plan-delta","delta":{"message":{"tool_plan":"I will use "}}}""",
                """{"type":"tool-plan-delta","delta":{"message":{"tool_plan":"the weather tool."}}}""",
                """{"type":"tool-call-start","index":0,"delta":{"message":{"tool_calls":{"id":"weather_e8p4","type":"function","function":{"name":"weather","arguments":""}}}}}""",
                """{"type":"tool-call-delta","index":0,"delta":{"message":{"tool_calls":{"function":{"arguments":"{\"location\":"}}}}}""",
                """{"type":"tool-call-delta","index":0,"delta":{"message":{"tool_calls":{"function":{"arguments":"\"SF\"}"}}}}}""",
                """{"type":"tool-call-end","index":0}""",
                """{"type":"message-end","delta":{"finish_reason":"TOOL_CALL"}}""",
            ),
        )

        val parts = model(server).doStream(call).stream.toList()

        assertEquals(
            "I will use the weather tool.",
            parts.filterIsInstance<StreamPart.ReasoningDelta>().joinToString("") { it.delta },
        )
        // The plan closes where the calls begin, so the tool blocks are not nested inside it.
        assertTrue(
            parts.indexOfFirst { it is StreamPart.ReasoningEnd } <
                parts.indexOfFirst { it is StreamPart.ToolInputStart },
        )
        val toolCall = parts.filterIsInstance<StreamPart.ToolCallPart>().single().toolCall
        assertEquals("weather_e8p4", toolCall.toolCallId)
        assertEquals("""{"location":"SF"}""", toolCall.input)
    }

    @Test
    fun `a streamed call for a tool that takes no arguments still replays as valid JSON`() = runTest {
        // The reference's recorded wire for such a tool: `arguments` opens empty and closes with no
        // delta at all. An empty string is not JSON, so a consumer parsing tool input gets nothing.
        val server = TestServer(
            sse(
                """{"type":"tool-call-start","index":0,"delta":{"message":{"tool_calls":{"id":"currentTime_y46a","type":"function","function":{"name":"currentTime","arguments":""}}}}}""",
                """{"type":"tool-call-end","index":0}""",
                """{"type":"message-end","delta":{"finish_reason":"TOOL_CALL"}}""",
            ),
        )

        val toolCall = model(server).doStream(call).stream.toList()
            .filterIsInstance<StreamPart.ToolCallPart>().single().toolCall

        assertEquals("currentTime", toolCall.toolName)
        assertEquals("{}", toolCall.input)
    }

    // ---- The non-streamed turn, where the citations live ------------------------------------------

    @Test
    fun `a generated turn carries reasoning, text and its citations`() = runTest {
        // The reference's recorded citations body, trimmed to one citation.
        val server = TestServer(
            TestServer.json(
                """
                {"id":"68475c80","message":{"role":"assistant",
                 "content":[{"type":"text","text":"The key benefit is automation of tasks."}],
                 "citations":[{"start":21,"end":40,"text":"automation of tasks",
                   "sources":[{"type":"document","id":"doc:0","document":{"id":"doc:0",
                     "text":"AI provides: 1. Automation of tasks","title":"benefits.txt"}}],
                   "type":"TEXT_CONTENT"}]},
                 "finish_reason":"COMPLETE",
                 "usage":{"billed_units":{"input_tokens":39,"output_tokens":27},
                          "tokens":{"input_tokens":1683,"output_tokens":62}}}
                """.trimIndent(),
            ),
        )

        val result = model(server).doGenerate(call)

        assertEquals("The key benefit is automation of tasks.", (result.content[0] as Content.Text).text)
        val source = result.content[1] as Content.Source.Document
        assertEquals("benefits.txt", source.title)
        // The span and the quoted text are what make a citation renderable as a footnote; a source
        // without them names a document and cannot be attached to anything in the answer.
        val citation = source.providerMetadata?.get("cohere")
        assertEquals(21, citation?.get("start").int())
        assertEquals("automation of tasks", citation?.get("text").string())
        assertEquals(FinishReason.Unified.Stop, result.finishReason.unified)
        assertEquals(1683, result.usage.inputTokens.total)
        // A non-streamed turn must say so on the wire, or the JSON reader is handed an SSE body.
        server.request().assertBodyMissing("stream")
    }

    @Test
    fun `a generated turn reads thinking content and the plan as reasoning`() = runTest {
        val server = TestServer(
            TestServer.json(
                """
                {"id":"53bcb235","message":{"role":"assistant","content":[
                  {"type":"thinking","thinking":"Okay, so I need to figure out what 2 + 2 is."},
                  {"type":"text","text":"2 + 2 = 4"}]},
                 "finish_reason":"COMPLETE",
                 "usage":{"tokens":{"input_tokens":1394,"output_tokens":582}}}
                """.trimIndent(),
            ),
        )

        val result = model(server).doGenerate(call)

        assertEquals(
            "Okay, so I need to figure out what 2 + 2 is.",
            (result.content[0] as Content.Reasoning).text,
        )
        assertEquals("2 + 2 = 4", (result.content[1] as Content.Text).text)
    }

    @Test
    fun `a tool defined as taking no arguments replays as an object, not the string null`() = runTest {
        // Cohere's own recorded answer for such a tool: `"arguments": "null"`. Replayed untouched it is
        // not an object, and every consumer that parses tool input gets a null where a record belongs.
        val server = TestServer(
            TestServer.json(
                """
                {"id":"316f0604","message":{"role":"assistant",
                 "tool_plan":"I will use the currentTime tool.",
                 "tool_calls":[{"id":"currentTime_tf4d","type":"function",
                   "function":{"name":"currentTime","arguments":"null"}}]},
                 "finish_reason":"TOOL_CALL","usage":{"tokens":{"input_tokens":1445,"output_tokens":43}}}
                """.trimIndent(),
            ),
        )

        val result = model(server).doGenerate(call)

        assertEquals("I will use the currentTime tool.", (result.content[0] as Content.Reasoning).text)
        assertEquals("{}", (result.content[1] as Content.ToolCall).input)
        assertEquals(FinishReason.Unified.ToolCalls, result.finishReason.unified)
    }

    // ---- Requests ---------------------------------------------------------------------------------

    @Test
    fun `the request body is Cohere's, field for field`() = runTest {
        val server = TestServer(sse(done))

        model(server).doStream(
            call.copy(
                maxOutputTokens = 100,
                temperature = 0.4,
                topP = 0.8,
                topK = 20,
                seed = 7,
                presencePenalty = 0.1,
                frequencyPenalty = 0.2,
                stopSequences = listOf("END"),
            ),
        ).stream.toList()

        // Whole-body, because a key nothing reads is a key whose absence nothing can see: `p`/`k` were
        // asserted while `seed` and both penalties were being dropped on the floor.
        server.request().assertBodyKeys(
            "model", "messages", "stream", "max_tokens", "temperature", "p", "k",
            "seed", "presence_penalty", "frequency_penalty", "stop_sequences",
        )
        server.request().assertBodyJson { body ->
            assertEquals("command-a-03-2025", body["model"].string())
            assertEquals("0.8", body["p"].string())
            assertEquals("20", body["k"].string())
            assertEquals("7", body["seed"].string())
            assertEquals("hi", body.arr("messages")?.single()?.jsonObject?.get("content").string())
        }
    }

    @Test
    fun `a named tool is pinned by sending only that tool, which is exact`() = runTest {
        val server = TestServer(sse(done))
        val tools = listOf(
            Tool.Function(name = "wanted", inputSchema = buildJsonObject { }),
            Tool.Function(name = "other", inputSchema = buildJsonObject { }),
        )

        val parts = model(server)
            .doStream(call.copy(tools = tools, toolChoice = ToolChoice.Specific("wanted")))
            .stream.toList()

        // Sending both tools with REQUIRED — "the closest available value" — let the model call `other`
        // for a choice that named `wanted`. Filtering the array is exact, so nothing is warned about.
        server.request().assertBodyJson { body ->
            assertEquals(
                listOf("wanted"),
                body.arr("tools")?.map { it.jsonObject.obj("function")?.get("name").string() },
            )
            assertEquals("REQUIRED", body["tool_choice"].string())
        }
        parts.filterIsInstance<StreamPart.StreamStart>().single().warnings.assertNoWarningAbout("toolChoice")
    }

    @Test
    fun `auto sends no tool_choice at all because there is no such value`() = runTest {
        val server = TestServer(sse(done))
        val tools = listOf(Tool.Function(name = "t", inputSchema = buildJsonObject { }))

        model(server).doStream(call.copy(tools = tools, toolChoice = ToolChoice.Auto)).stream.toList()

        server.request().assertBodyMissing("tool_choice")
    }

    @Test
    fun `tool_choice is omitted entirely when there are no tools`() = runTest {
        val server = TestServer(sse(done))

        model(server).doStream(call.copy(toolChoice = ToolChoice.Required)).stream.toList()

        server.request().assertBodyMissing("tool_choice")
        server.request().assertBodyMissing("tools")
    }

    @Test
    fun `a server-side tool Cohere does not run is warned about rather than sent`() = runTest {
        val server = TestServer(sse(done))
        val tools = listOf(
            Tool.ProviderDefined(name = "search", id = "openai.web_search", args = buildJsonObject { }),
        )

        val parts = model(server).doStream(call.copy(tools = tools)).stream.toList()

        parts.filterIsInstance<StreamPart.StreamStart>().single().warnings
            .assertUnsupported("provider-defined tool openai.web_search", "Cohere runs no server-side tools; it was dropped.")
        server.request().assertBodyMissing("tools")
    }

    @Test
    fun `an effort level becomes a thinking budget, and none becomes disabled`() = runTest {
        val enabled = TestServer(sse(done))
        model(enabled).doStream(call.copy(reasoning = ReasoningEffort.Low)).stream.toList()
        enabled.request().assertBodyJson { body ->
            assertEquals("enabled", body.obj("thinking")?.get("type").string())
            assertEquals(3277, body.obj("thinking")?.get("token_budget").int())
        }

        val off = TestServer(sse(done))
        model(off).doStream(call.copy(reasoning = ReasoningEffort.None)).stream.toList()
        // A reasoning model with no `thinking` field thinks at its own default, so "do not think" has
        // to be transmitted rather than expressed by leaving the field out.
        off.request().assertBodyJson { assertEquals("disabled", it.obj("thinking")?.get("type").string()) }

        val silent = TestServer(sse(done))
        model(silent).doStream(call).stream.toList()
        silent.request().assertBodyMissing("thinking")
    }

    @Test
    fun `an explicit token budget from providerOptions wins over the effort ladder`() = runTest {
        val server = TestServer(sse(done))
        val options = mapOf(
            "cohere" to buildJsonObject {
                put(
                    "thinking",
                    buildJsonObject {
                        put("type", "enabled")
                        put("tokenBudget", 4096)
                    },
                )
            },
        )

        model(server).doStream(
            call.copy(reasoning = ReasoningEffort.High, providerOptions = options),
        ).stream.toList()

        server.request().assertBodyJson { body ->
            assertEquals(4096, body.obj("thinking")?.get("token_budget").int())
        }
    }

    @Test
    fun `structured output goes out as json_object with the schema`() = runTest {
        val server = TestServer(sse(done))
        val schema = buildJsonObject { put("type", "object") }

        model(server).doStream(
            call.copy(responseFormat = ResponseFormat.Json(schema = schema)),
        ).stream.toList()

        server.request().assertBodyJson { body ->
            assertEquals("json_object", body.obj("response_format")?.get("type").string())
            assertEquals(schema, body.obj("response_format")?.get("json_schema"))
        }
    }

    // ---- Documents and images ---------------------------------------------------------------------

    @Test
    fun `an attached document becomes a top-level document, which is what makes it citable`() = runTest {
        val server = TestServer(sse(done))
        val prompt = listOf(
            ModelMessage.User(
                listOf(
                    UserPart.Text("What are the benefits?"),
                    UserPart.File(
                        data = FileData.Text("AI provides: automation of tasks"),
                        mediaType = "text/plain",
                        filename = "benefits.txt",
                    ),
                ),
            ),
        )

        model(server).doStream(call.copy(prompt = prompt)).stream.toList()

        server.request().assertBodyJson { body ->
            // A document attached to the user turn instead is dropped by this API, taking the whole RAG
            // half of the product with it.
            val document = body.arr("documents")?.single()?.jsonObject?.obj("data")
            assertEquals("AI provides: automation of tasks", document?.get("text").string())
            assertEquals("benefits.txt", document?.get("title").string())
            // The turn itself stays a bare string, which is the form the non-vision models accept.
            assertEquals(
                "What are the benefits?",
                body.arr("messages")?.single()?.jsonObject?.get("content").string(),
            )
        }
    }

    @Test
    fun `an image turn sends parts, with the fidelity the caller asked for`() = runTest {
        val server = TestServer(sse(done))
        val prompt = listOf(
            ModelMessage.User(
                listOf(
                    UserPart.Text("What is this?"),
                    UserPart.File(
                        data = FileData.Bytes(byteArrayOf(1, 2, 3)),
                        mediaType = "image/png",
                        providerOptions = mapOf("cohere" to buildJsonObject { put("detail", "high") }),
                    ),
                ),
            ),
        )

        model(server).doStream(call.copy(prompt = prompt)).stream.toList()

        server.request().assertBodyJson { body ->
            val content = body.arr("messages")?.single()?.jsonObject?.let { it["content"] }
            val parts = (content as? kotlinx.serialization.json.JsonArray)?.map { it.jsonObject }
            assertEquals("text", parts?.get(0)?.get("type").string())
            val image = parts?.get(1)?.obj("image_url")
            assertEquals("data:image/png;base64,AQID", image?.get("url").string())
            assertEquals("high", image?.get("detail").string())
        }
    }

    @Test
    fun `a document URL is refused rather than sent as the document's text`() = runTest {
        val server = TestServer(sse(done))
        val prompt = listOf(
            ModelMessage.User(
                listOf(UserPart.File(FileData.Url("https://x/doc.pdf"), mediaType = "application/pdf")),
            ),
        )

        // Silently sending the URL as the text would have the model summarize a link.
        assertFailsWith<UnsupportedFunctionalityError> {
            model(server).doStream(call.copy(prompt = prompt))
        }
    }

    // ---- Replay -----------------------------------------------------------------------------------

    @Test
    fun `a replayed turn sends its reasoning back as tool_plan`() = runTest {
        val server = TestServer(sse(done))
        val prompt = listOf(
            ModelMessage.User(listOf(UserPart.Text("hi"))),
            ModelMessage.Assistant(
                listOf(
                    AssistantPart.Reasoning("earlier plan"),
                    AssistantPart.ToolCall("c1", "lookup", """{"q":"x"}"""),
                ),
            ),
            ModelMessage.Tool(listOf(ToolPart.Result("c1", "lookup", ToolOutput.Text("result")))),
        )

        model(server).doStream(call.copy(prompt = prompt)).stream.toList()

        server.request().assertBodyJson { body ->
            val messages = body.arr("messages").orEmpty().map { it.jsonObject }
            val assistant = messages.single { it["role"].string() == "assistant" }
            // Replayed where it came from, so the model sees its own plan on the next round.
            assertEquals("earlier plan", assistant["tool_plan"].string())
            assertEquals("c1", assistant.arr("tool_calls")?.single()?.jsonObject?.get("id").string())
            val tool = messages.single { it["role"].string() == "tool" }
            assertEquals("c1", tool["tool_call_id"].string())
            assertEquals("result", tool["content"].string())
        }
    }

    // ---- Finish reasons and usage -----------------------------------------------------------------

    @Test
    fun `a stop sequence is a normal stop, not an unknown one`() = runTest {
        val server = TestServer(
            sse("""{"type":"message-end","delta":{"finish_reason":"STOP_SEQUENCE"}}"""),
        )

        val finish = model(server).doStream(call).stream.toList()
            .filterIsInstance<StreamPart.Finish>().single()

        // Mapped to Other, a turn that ended exactly as the caller asked reads as one that failed, and
        // every loop switching on the unified reason takes the wrong branch.
        assertEquals(FinishReason.Unified.Stop, finish.finishReason.unified)
        assertEquals("STOP_SEQUENCE", finish.finishReason.raw)
    }

    @Test
    fun `usage and Cohere's own finish reasons are mapped`() = runTest {
        val server = TestServer(
            sse(
                """{"type":"message-end","delta":{"finish_reason":"MAX_TOKENS","usage":{"tokens":{"input_tokens":12,"output_tokens":34}}}}""",
            ),
        )

        val finish = model(server).doStream(call).stream.toList()
            .filterIsInstance<StreamPart.Finish>().single()

        assertEquals(FinishReason.Unified.Length, finish.finishReason.unified)
        assertEquals("MAX_TOKENS", finish.finishReason.raw)
        assertEquals(12, finish.usage.inputTokens.total)
        assertEquals(34, finish.usage.outputTokens.total)
    }
}

/** Documents ranked and vectors embedded — the two modalities Cohere is reached for beyond chat. */
class CohereModalitiesTest {

    private val documents = RerankingDocuments

    @Test
    fun `reranking sends the query and documents, and the ranking is the recorded one`() = runTest {
        // The reference's recorded rerank body: already ordered by score, indices into what we sent.
        val server = TestServer(
            TestServer.json(
                """{"id":"b44fe75b","results":[{"index":1,"relevance_score":0.10183054},
                    {"index":0,"relevance_score":0.03762639}],"meta":{}}""",
            ),
        )

        val result = rerankingModel(server).doRerank(
            RerankingCallOptions(
                documents = documents,
                query = "capital of France",
                topN = 2,
            ),
        )

        server.request().assertBodyKeys("model", "query", "documents", "top_n")
        server.request().assertBodyJson { body ->
            assertEquals("rerank-v3.5", body["model"].string())
            assertEquals("capital of France", body["query"].string())
            assertEquals(listOf("Paris is in France.", "Berlin is in Germany."), body.arr("documents")?.map { it.string() })
            assertEquals(2, body["top_n"].int())
        }
        assertEquals(listOf(1, 0), result.ranking.map { it.index })
        assertEquals(0.10183054, result.ranking[0].relevanceScore)
    }

    @Test
    fun `structured documents are serialized, and the caller is told the ranking changes`() = runTest {
        val server = TestServer(TestServer.json("""{"results":[{"index":0,"relevance_score":0.9}]}"""))

        val result = rerankingModel(server).doRerank(
            RerankingCallOptions(
                documents = RerankingCallOptions.Documents.Objects(
                    listOf(buildJsonObject { put("title", "Paris") }),
                ),
                query = "France",
            ),
        )

        // The JSON punctuation gets scored alongside the content, which is a real difference in the
        // ranking rather than a formatting detail — Together AI takes the objects natively.
        result.warnings.assertCompatibility(
            "object documents",
            "Cohere ranks text only; each document was serialized to JSON.",
        )
        assertEquals("""{"title":"Paris"}""", server.request().bodyJson().arr("documents")?.single().string())
    }

    @Test
    fun `reranking options ride in providerOptions and headers merge`() = runTest {
        val server = TestServer(TestServer.json("""{"results":[]}"""))

        rerankingModel(server).doRerank(
            RerankingCallOptions(
                documents = documents,
                query = "q",
                providerOptions = mapOf(
                    "cohere" to buildJsonObject {
                        put("maxTokensPerDoc", 512)
                        put("priority", 1)
                    },
                ),
                headers = mapOf("X-Trace" to "abc"),
            ),
        )

        server.request().assertBodyKeys("model", "query", "documents", "max_tokens_per_doc", "priority")
        server.request().assertHeader("X-Trace", "abc")
        server.request().assertHeader("Authorization", "Bearer k")
    }

    @Test
    fun `embeddings come back from the float key, not an OpenAI data array`() = runTest {
        // The reference's recorded embed body. Read as `data[].embedding` this is an empty list.
        val server = TestServer(
            TestServer.json(
                """{"id":"f5aa3e7b","texts":["a","b"],
                    "embeddings":{"float":[[0.033,0.02],[-0.046,0.0003]]},
                    "meta":{"billed_units":{"input_tokens":10}},"response_type":"embeddings_by_type"}""",
            ),
        )

        val result = embeddingModel(server).doEmbed(
            EmbeddingCallOptions(values = listOf("a", "b")),
        )

        assertEquals(listOf(listOf(0.033, 0.02), listOf(-0.046, 0.0003)), result.embeddings)
        assertEquals(10, result.usage)
        server.request().assertBodyKeys("model", "texts", "embedding_types", "input_type")
        server.request().assertBodyJson { body ->
            assertEquals(listOf("a", "b"), body.arr("texts")?.map { it.string() })
            // Required by every v3 model, with no server-side default; `float` is the only type the
            // contract's `List<Double>` can hold.
            assertEquals("search_query", body["input_type"].string())
            assertEquals(listOf("float"), body.arr("embedding_types")?.map { it.string() })
        }
    }

    @Test
    fun `the indexing side of the pair and the output size come from providerOptions`() = runTest {
        val server = TestServer(TestServer.json("""{"embeddings":{"float":[]}}"""))

        embeddingModel(server).doEmbed(
            EmbeddingCallOptions(
                values = listOf("a"),
                providerOptions = mapOf(
                    "cohere" to buildJsonObject {
                        put("inputType", "search_document")
                        put("truncate", "START")
                        put("outputDimension", 512)
                    },
                ),
            ),
        )

        server.request().assertBodyJson { body ->
            // Getting the query/document pair backwards degrades every retrieval score without failing.
            assertEquals("search_document", body["input_type"].string())
            assertEquals("START", body["truncate"].string())
            assertEquals(512, body["output_dimension"].int())
        }
    }

    @Test
    fun `a batch past 96 is refused before the request goes out`() = runTest {
        val server = TestServer(TestServer.json("{}"))

        assertFailsWith<TooManyEmbeddingValuesForCallError> {
            embeddingModel(server).doEmbed(
                EmbeddingCallOptions(values = List(97) { "x" }),
            )
        }
        assertEquals(0, server.callCount)
    }

    @Test
    fun `every modality Cohere serves is reachable from the provider, and no others`() {
        val provider = CohereProvider(
            client = HttpClient(TestServer(TestServer.json("{}")).engine()),
            apiKey = "k",
        )

        assertTrue(provider.languageModel("command-a-03-2025") is CohereLanguageModel)
        assertTrue(provider.embeddingModel("embed-v4.0") is CohereEmbeddingModel)
        assertTrue(provider.rerankingModel("rerank-v3.5") is CohereRerankingModel)
        assertNull(provider.imageModel("anything"))
        assertNull(provider.speechModel("anything"))
        assertNull(provider.transcriptionModel("anything"))
    }

    private fun rerankingModel(server: TestServer) = CohereRerankingModel(
        modelId = "rerank-v3.5",
        http = server.http(),
        url = "https://api.cohere.com/v2/rerank",
        headers = mapOf("Authorization" to "Bearer k"),
    )

    private fun embeddingModel(server: TestServer) = CohereEmbeddingModel(
        modelId = "embed-v4.0",
        http = server.http(),
        url = "https://api.cohere.com/v2/embed",
        headers = mapOf("Authorization" to "Bearer k"),
    )

    private companion object {
        val RerankingDocuments = RerankingCallOptions.Documents.Text(
            listOf("Paris is in France.", "Berlin is in Germany."),
        )
    }
}
