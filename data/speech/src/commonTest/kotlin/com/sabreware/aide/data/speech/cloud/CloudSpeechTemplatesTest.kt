package com.sabreware.aide.data.speech.cloud

import com.sabreware.aide.core.domain.connection.VendorId
import com.sabreware.aide.core.domain.fakes.FakeModelSelectionStore
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.model.ModelSelection
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.speech.CloudSpeechCatalog
import com.sabreware.aide.core.domain.speech.CloudSpeechModelSpec
import com.sabreware.aide.core.domain.speech.resolveRemoteName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudSpeechTemplatesTest {

    private val openai = ProviderId("openai-test01")
    private val openaiWork = ProviderId("openai-test02")
    private val eleven = ProviderId("elevenlabs-test03")
    private val gemini = ProviderId("gemini-test04")
    private val anthropic = ProviderId("anthropic-test05")

    private class Catalog(models: List<CloudSpeechModelSpec>) : CloudSpeechCatalog {
        override val models: StateFlow<List<CloudSpeechModelSpec>?> = MutableStateFlow(models)
    }

    private val catalog = Catalog(
        CloudSpeechTemplates.forConnection(VendorId.OPENAI_COMPATIBLE, openai) +
            CloudSpeechTemplates.forConnection(VendorId.OPENAI_COMPATIBLE, openaiWork) +
            CloudSpeechTemplates.forConnection(VendorId.ELEVENLABS, eleven) +
            CloudSpeechTemplates.forConnection(VendorId.GEMINI, gemini) +
            CloudSpeechTemplates.forConnection(VendorId.ANTHROPIC, anthropic),
    )

    @Test
    fun `ids are namespaced by connection and unique`() {
        assertTrue(catalog.all.all { it.id == "${it.provider.value}:${it.remoteName}" })
        assertEquals(catalog.all.size, catalog.all.map { it.id }.toSet().size)
        assertTrue(catalog.all.none { it.requiresDownload })
    }

    @Test
    fun `two accounts of one vendor are two sets of models`() {
        assertEquals(
            catalog.forProvider(openai, Modality.Asr).map { it.remoteName },
            catalog.forProvider(openaiWork, Modality.Asr).map { it.remoteName },
        )
        assertEquals(openaiWork, catalog.findById("openai-test02:whisper-1")?.provider)
    }

    @Test
    fun `a vendor that does not speak contributes nothing`() {
        assertTrue(CloudSpeechTemplates.forConnection(VendorId.ANTHROPIC, anthropic).isEmpty())
        assertNull(CloudSpeechTemplates.defaultRemoteName(VendorId.ANTHROPIC, Modality.Tts))
    }

    @Test
    fun `every connection that serves a role has exactly one default for it`() {
        catalog.all.groupBy { it.provider to it.modality }.forEach { (key, rows) ->
            assertEquals(1, rows.count { it.isDefault }, "defaults for $key")
        }
        assertEquals("gpt-4o-mini-transcribe", catalog.default(openai, Modality.Asr)?.remoteName)
        assertEquals("eleven_multilingual_v2", catalog.default(eleven, Modality.Tts)?.remoteName)
        assertEquals("gemini-2.5-flash-preview-tts", catalog.default(gemini, Modality.Tts)?.remoteName)
        assertEquals("gpt-4o-mini-tts", CloudSpeechTemplates.defaultRemoteName(VendorId.OPENAI_COMPATIBLE, Modality.Tts))
    }

    @Test
    fun `findById knows whose a model is`() {
        assertEquals(eleven, catalog.findById("elevenlabs-test03:scribe_v1")?.provider)
        assertEquals(null, catalog.findById("sherpa-onnx-zipformer-en"))
    }

    @Test
    fun `resolveRemoteName prefers the active pick of the same connection`() = runTest {
        val selection = FakeModelSelectionStore(ModelSelection(activeByModality = mapOf("asr" to "openai-test01:whisper-1")))

        assertEquals("whisper-1", catalog.resolveRemoteName(selection, openai, Modality.Asr))
    }

    @Test
    fun `resolveRemoteName falls back to the default when the pick is another connection's`() = runTest {
        val selection = FakeModelSelectionStore(ModelSelection(activeByModality = mapOf("asr" to "openai-test01:whisper-1")))

        assertEquals("scribe_v1", catalog.resolveRemoteName(selection, eleven, Modality.Asr))
        // Same vendor, other account: its own default, not the first account's pick.
        assertEquals("gpt-4o-mini-transcribe", catalog.resolveRemoteName(selection, openaiWork, Modality.Asr))
        assertEquals("gpt-4o-mini-tts", catalog.resolveRemoteName(FakeModelSelectionStore(), openai, Modality.Tts))
    }

    @Test
    fun `a connection with no model for the role is a wiring bug and says so`() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            catalog.resolveRemoteName(FakeModelSelectionStore(), anthropic, Modality.Tts)
        }
        assertTrue(anthropic.value in error.message.orEmpty())
        assertTrue(catalog.forProvider(anthropic, Modality.Tts).isEmpty())
    }
}
