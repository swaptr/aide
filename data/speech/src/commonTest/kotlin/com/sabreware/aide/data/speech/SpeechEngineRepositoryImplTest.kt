package com.sabreware.aide.data.speech

import com.sabreware.aide.core.domain.llm.Surface
import com.sabreware.aide.core.domain.fakes.FakeModelSelectionStore
import com.sabreware.aide.core.domain.fakes.FakePreferenceStore
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.model.ModelSelection
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.model.ResidencyHandle
import com.sabreware.aide.core.domain.model.ResidencyManager
import com.sabreware.aide.core.domain.model.ResidentModel
import com.sabreware.aide.core.domain.speech.SpeechAvailability
import com.sabreware.aide.core.domain.speech.SpeechEngineRepository.Role
import com.sabreware.aide.core.domain.speech.SpeechPrefs
import com.sabreware.aide.core.domain.speech.SpeechProvider
import com.sabreware.aide.core.domain.speech.SpeechProviderRegistry
import com.sabreware.aide.core.domain.speech.SpeechRecognizerEngine
import com.sabreware.aide.core.domain.speech.SpeechResolutionPolicy
import com.sabreware.aide.core.domain.speech.SpeechSynthesizerEngine
import com.sabreware.aide.core.domain.speech.VadEngine
import com.sabreware.aide.core.domain.connection.VendorId
import com.sabreware.aide.core.domain.speech.CloudSpeechCatalog
import com.sabreware.aide.core.domain.speech.CloudSpeechModelSpec
import com.sabreware.aide.data.speech.cloud.CloudSpeechTemplates
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * The resolution ladder is the whole reason there used to be an android [SpeechEngineRepository] and a
 * desktop one. It is now data, so these cases pin both platforms' behaviour in one place.
 */
class SpeechEngineRepositoryImplTest {

    private class StubProvider(
        override val id: ProviderId,
        private val availability: SpeechAvailability,
    ) : SpeechProvider {
        override val stt: SpeechRecognizerEngine? = null
        override val tts: SpeechSynthesizerEngine? = null
        override val vad: VadEngine? = null
        override suspend fun availability(): SpeechAvailability = availability
    }

    private class RecordingResidency : ResidencyManager {
        val acquired = mutableListOf<String>()
        val released = mutableListOf<Long>()
        override suspend fun acquire(model: ResidentModel, owner: Surface?): ResidencyHandle {
            acquired += model.key
            return object : ResidencyHandle {
                override suspend fun release(keepAliveMs: Long) {
                    released += keepAliveMs
                }
            }
        }

        override fun residents(): List<ResidencyManager.Resident> = emptyList()

        override fun onTrimMemory(level: Int) = Unit
    }

    private object NoopResidency : ResidencyManager {
        override suspend fun acquire(model: ResidentModel, owner: Surface?): ResidencyHandle =
            object : ResidencyHandle {
                override suspend fun release(keepAliveMs: Long) = Unit
            }

        override fun residents(): List<ResidencyManager.Resident> = emptyList()

        override fun onTrimMemory(level: Int) = Unit
    }

    /** The speech rows of the one OpenAI connection these tests have, or null while connections are unread. */
    private class FakeCloudCatalog(models: List<CloudSpeechModelSpec>?) : CloudSpeechCatalog {
        override val models = MutableStateFlow(models)
    }

    private val openai = ProviderId("openai-test01")
    private val openaiRows = CloudSpeechTemplates.forConnection(VendorId.OPENAI_COMPATIBLE, openai)

    private val capable = SpeechAvailability(canStt = true, canTts = true, canVad = true)
    private val incapable = SpeechAvailability(canStt = false, canTts = false, canVad = false)

    private fun repo(
        providers: List<SpeechProvider>,
        ladder: List<ProviderId>,
        pinned: String? = null,
        activeModels: Map<String, String> = emptyMap(),
        connected: StateFlow<List<SpeechProvider>?> = MutableStateFlow(emptyList()),
        cloud: CloudSpeechCatalog = FakeCloudCatalog(openaiRows),
        residency: ResidencyManager = NoopResidency,
    ) = SpeechEngineRepositoryImpl(
        providers = SpeechProviderRegistry(providers, connected),
        policy = SpeechResolutionPolicy(ladder),
        prefs = FakePreferenceStore(SpeechPrefs.Provider to pinned),
        selection = FakeModelSelectionStore(ModelSelection(activeByModality = activeModels)),
        residency = residency,
        cloudModels = cloud,
    )

    @Test
    fun `warming up dictation goes through residency so the weights can be freed`() = runTest {
        val residency = RecordingResidency()
        val repo = repo(
            providers = listOf(StubProvider(ProviderId.SHERPA, capable)),
            ladder = listOf(ProviderId.SHERPA),
            residency = residency,
        )

        repo.warmUpStt()

        assertEquals(1, residency.acquired.size, "the preload takes a slot instead of loading behind its back")
        assertEquals(1, residency.released.size, "and hands it straight to the idle timer")
    }

    private val androidLadder = listOf(ProviderId.SHERPA, ProviderId.ANDROID_SYSTEM)

    @Test
    fun `a capable pinned provider wins over the ladder`() = runTest {
        val repo = repo(
            providers = listOf(
                StubProvider(ProviderId.SHERPA, capable),
                StubProvider(ProviderId.ANDROID_SYSTEM, capable),
            ),
            ladder = androidLadder,
            pinned = ProviderId.ANDROID_SYSTEM_KEY,
        )

        assertEquals(ProviderId.ANDROID_SYSTEM, repo.resolve(Role.STT).id)
        assertEquals(ProviderId.ANDROID_SYSTEM, repo.currentProviderFlow.value)
    }

    // Pinning Sherpa before its weights land must not strand the user with a mute app.
    @Test
    fun `an incapable pinned provider falls through to the ladder`() = runTest {
        val repo = repo(
            providers = listOf(
                StubProvider(ProviderId.SHERPA, incapable),
                StubProvider(ProviderId.ANDROID_SYSTEM, capable),
            ),
            ladder = androidLadder,
            pinned = ProviderId.SHERPA_KEY,
        )

        assertEquals(ProviderId.ANDROID_SYSTEM, repo.resolve(Role.STT).id)
    }

    @Test
    fun `with nothing pinned the ladder is walked in order`() = runTest {
        val repo = repo(
            providers = listOf(
                StubProvider(ProviderId.SHERPA, capable),
                StubProvider(ProviderId.ANDROID_SYSTEM, capable),
            ),
            ladder = androidLadder,
        )

        assertEquals(ProviderId.SHERPA, repo.resolve(Role.TTS).id)
    }

    // Capability is per role: Sherpa may have TTS weights and no ASR weights.
    @Test
    fun `capability is judged per role`() = runTest {
        val repo = repo(
            providers = listOf(
                StubProvider(ProviderId.SHERPA, SpeechAvailability(canStt = false, canTts = true, canVad = false)),
                StubProvider(ProviderId.ANDROID_SYSTEM, capable),
            ),
            ladder = androidLadder,
        )

        assertEquals(ProviderId.SHERPA, repo.resolve(Role.TTS).id)
        assertEquals(ProviderId.ANDROID_SYSTEM, repo.resolve(Role.STT).id)
    }

    // The terminal is returned WITHOUT a capability check: the streaming APIs report the failure as an
    // Error event, so resolve() must hand them a provider rather than throw.
    @Test
    fun `the terminal provider is returned even when it cannot serve the role`() = runTest {
        val repo = repo(
            providers = listOf(StubProvider(ProviderId.SHERPA, incapable)),
            ladder = listOf(ProviderId.SHERPA),
        )

        assertEquals(ProviderId.SHERPA, repo.resolve(Role.STT).id)
    }

    @Test
    fun `a ladder entry nobody contributed is skipped`() = runTest {
        val repo = repo(
            providers = listOf(StubProvider(ProviderId.ANDROID_SYSTEM, capable)),
            ladder = androidLadder,
        )

        assertEquals(ProviderId.ANDROID_SYSTEM, repo.resolve(Role.VAD).id)
    }

    // Picking a cloud transcription model in Models IS the intent: Auto must honour it without a second
    // "provider" preference, and only for the role whose slot names it.
    @Test
    fun `in Auto the vendor of the active cloud model is tried first`() = runTest {
        val repo = repo(
            providers = listOf(
                StubProvider(ProviderId.SHERPA, capable),
                StubProvider(ProviderId.ANDROID_SYSTEM, capable),
                StubProvider(openai, capable),
            ),
            ladder = androidLadder,
            activeModels = mapOf(Modality.Asr.value to "openai-test01:gpt-4o-mini-transcribe"),
        )

        assertEquals(openai, repo.resolve(Role.STT).id)
        assertEquals(ProviderId.SHERPA, repo.resolve(Role.TTS).id)
    }

    // A vendor whose key was removed is not capable; the ladder carries on rather than stranding the user.
    @Test
    fun `an incapable cloud vendor falls through to the ladder`() = runTest {
        val repo = repo(
            providers = listOf(
                StubProvider(ProviderId.SHERPA, capable),
                StubProvider(openai, incapable),
            ),
            ladder = listOf(ProviderId.SHERPA),
            activeModels = mapOf(Modality.Asr.value to "openai-test01:gpt-4o-mini-transcribe"),
        )

        assertEquals(ProviderId.SHERPA, repo.resolve(Role.STT).id)
    }

    @Test
    fun `a pinned provider beats the active cloud model`() = runTest {
        val repo = repo(
            providers = listOf(
                StubProvider(ProviderId.SHERPA, capable),
                StubProvider(openai, capable),
            ),
            ladder = listOf(ProviderId.SHERPA),
            pinned = ProviderId.SHERPA_KEY,
            activeModels = mapOf(Modality.Asr.value to "openai-test01:gpt-4o-mini-transcribe"),
        )

        assertEquals(ProviderId.SHERPA, repo.resolve(Role.STT).id)
    }

    // A cloud vendor is never a silent fallback: it enters through a pin or an active pick, not the ladder.
    @Test
    fun `an on-device active model leaves the ladder alone`() = runTest {
        val repo = repo(
            providers = listOf(
                StubProvider(ProviderId.SHERPA, capable),
                StubProvider(openai, capable),
            ),
            ladder = listOf(ProviderId.SHERPA),
            activeModels = mapOf(Modality.Asr.value to "sherpa-onnx-streaming-zipformer-en-2023-06-26"),
        )

        assertEquals(ProviderId.SHERPA, repo.resolve(Role.STT).id)
    }

    // A cold start: the connections document has not been read, so the cloud pick's owner and provider are
    // not known yet. Resolving must WAIT for them rather than silently fall back to the ladder.
    @Test
    fun `an active cloud pick waits for the connections instead of falling back`() = runTest {
        val connected = MutableStateFlow<List<SpeechProvider>?>(null)
        val cloud = FakeCloudCatalog(null)
        val repo = repo(
            providers = listOf(StubProvider(ProviderId.SHERPA, capable)),
            ladder = listOf(ProviderId.SHERPA),
            activeModels = mapOf(Modality.Asr.value to "openai-test01:gpt-4o-mini-transcribe"),
            connected = connected,
            cloud = cloud,
        )

        val resolved = async(start = CoroutineStart.UNDISPATCHED) { repo.resolve(Role.STT).id }
        runCurrent()
        assertFalse(resolved.isCompleted)

        cloud.models.value = openaiRows
        connected.value = listOf(StubProvider(openai, capable))

        assertEquals(openai, resolved.await())
    }

    // A pinned connection that the user has since removed is not an error: once the connections are read
    // and it is absent, the ladder carries on.
    @Test
    fun `a pinned connection that no longer exists falls through to the ladder`() = runTest {
        val repo = repo(
            providers = listOf(StubProvider(ProviderId.SHERPA, capable)),
            ladder = listOf(ProviderId.SHERPA),
            pinned = "openai-gone00",
        )

        assertEquals(ProviderId.SHERPA, repo.resolve(Role.STT).id)
    }

    @Test
    fun `a missing terminal is a wiring bug and says so`() = runTest {
        val repo = repo(providers = emptyList(), ladder = androidLadder)

        assertFailsWith<IllegalStateException> { repo.resolve(Role.STT) }
    }
}
