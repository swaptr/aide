package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.SkillFile
import com.sabreware.aide.aisdk.SkillUploadOptions
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.testing.TestServer
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The Skills API against `skills/openai-skills.test.ts` and its recorded `openai-skill-create.json`.
 *
 * The assertion that separates this from the Anthropic port: ONE request. OpenAI's create response
 * already names and describes the skill, so there is no version fetch to make.
 */
class OpenAISkillsTest {

    private val fileContent = "console.log(\"hello\")".encodeToByteArray()

    private fun skills(server: TestServer) =
        OpenAIProvider(client = HttpClient(server.engine()), apiKey = "test-api-key").skills()

    private fun upload(
        displayTitle: String? = null,
        files: List<SkillFile> = listOf(SkillFile("index.ts", FileData.Bytes(fileContent))),
    ) = SkillUploadOptions(files = files, displayTitle = displayTitle)

    @Test
    fun `files go out as untyped files parts named by their path`() = runTest {
        val server = TestServer(TestServer.json(OpenAIFixtures.skillCreate))

        skills(server).uploadSkill(upload())

        val call = server.request()
        assertEquals("POST", call.method)
        assertEquals("v1/skills", call.path)
        call.assertMultipartFile("files[]", fileName = "index.ts")
        // The reference uploads untyped blobs; a guessed content type would be a wire change.
        assertNull(call.multipartFiles.getValue("files[]").contentType)
        assertTrue("console.log(\"hello\")" in call.bodyText)
    }

    @Test
    fun `the bearer token authenticates the upload`() = runTest {
        val server = TestServer(TestServer.json(OpenAIFixtures.skillCreate))

        skills(server).uploadSkill(upload())

        server.request().assertHeader("Authorization", "Bearer test-api-key")
    }

    @Test
    fun `the create response already names the skill, so one upload is one request`() = runTest {
        val server = TestServer(TestServer.json(OpenAIFixtures.skillCreate))

        val result = skills(server).uploadSkill(upload())

        assertEquals(1, server.callCount)
        assertEquals(
            mapOf(OPENAI_PROVIDER_ID to "skill_699fc58f408c8191825d8d06ae75fd5c06de7b381a5db7f5"),
            result.providerReference,
        )
        assertEquals("test-capture-skill", result.name)
        assertEquals("A test skill for fixture capture", result.description)
        assertEquals("1", result.latestVersion)
        assertEquals(
            """{"defaultVersion":"1","createdAt":1772078479}""",
            result.providerMetadata?.get(OPENAI_PROVIDER_ID).toString(),
        )
    }

    @Test
    fun `a display title has no field here and is reported rather than sent`() = runTest {
        val server = TestServer(TestServer.json(OpenAIFixtures.skillCreate))

        val result = skills(server).uploadSkill(upload(displayTitle = "My Skill"))

        assertEquals(listOf(Warning.Unsupported(feature = "displayTitle")), result.warnings)
        assertTrue("display_title" !in server.request().multipart)
    }

    @Test
    fun `no warnings when nothing was dropped`() = runTest {
        val server = TestServer(TestServer.json(OpenAIFixtures.skillCreate))

        val result = skills(server).uploadSkill(upload())

        assertEquals(emptyList(), result.warnings)
    }

    @Test
    fun `binary content is uploaded as-is`() = runTest {
        val server = TestServer(TestServer.json(OpenAIFixtures.skillCreate))

        val result = skills(server).uploadSkill(
            upload(files = listOf(SkillFile("data.bin", FileData.Bytes(byteArrayOf(0x48, 0x65, 0x6c, 0x6c, 0x6f))))),
        )

        assertEquals(
            mapOf(OPENAI_PROVIDER_ID to "skill_699fc58f408c8191825d8d06ae75fd5c06de7b381a5db7f5"),
            result.providerReference,
        )
        val call = server.request()
        call.assertMultipartFile("files[]", fileName = "data.bin")
        assertTrue("Hello" in call.bodyText)
    }

    @Test
    fun `a file that is a URL or a reference is refused before anything is sent`() = runTest {
        val server = TestServer(TestServer.json(OpenAIFixtures.skillCreate))

        assertFailsWith<InvalidArgumentError> {
            skills(server).uploadSkill(
                upload(files = listOf(SkillFile("SKILL.md", FileData.Url("https://example.com/SKILL.md")))),
            )
        }
        assertEquals(0, server.callCount)
    }
}
