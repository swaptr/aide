package com.sabreware.aide.feature.tasks.domain
import com.sabreware.aide.core.domain.usecase.AcquireModelUseCase
import com.sabreware.aide.core.domain.usecase.ResidencyDurations
import com.sabreware.aide.core.domain.model.ChatModelSpec
import com.sabreware.aide.core.domain.usecase.ResolveActiveModelUseCase

import com.sabreware.aide.core.domain.llm.LlmEngineRepository
import com.sabreware.aide.core.domain.llm.Surface
import com.sabreware.aide.core.domain.model.ResidencyHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion

// Engine residency is managed via AcquireModelUseCase + the ResidencyManager (Phase 5): each task
// acquires the model and releases it with the IME keepAlive, which idle-unloads after the keyboard
// goes quiet. The IME no longer unloads the engine directly.
class RunTaskUseCase(
    private val tasks: TaskRepository,
    private val resolveActiveModel: ResolveActiveModelUseCase,
    private val engineRepo: LlmEngineRepository,
    private val acquireModel: AcquireModelUseCase,
) {

    sealed interface Event {
        data class Warming(val modelDisplayName: String) : Event
        data class Streaming(val text: String) : Event
        data class Done(val text: String) : Event
        data class Error(val message: String) : Event
    }

    fun invoke(taskId: String, fieldText: String): Flow<Event> = flow {
        val task = tasks.getById(taskId) ?: run {
            emit(Event.Error("Task '$taskId' not found.")); return@flow
        }
        if (fieldText.isBlank()) {
            emit(Event.Error("No text in field.")); return@flow
        }
        val prompt = task.buildPrompt(fieldText)
        runPrompt(prompt).collect { event ->
            emit(event)
            if (event is Event.Done) tasks.markUsed(taskId)
        }
    }

    fun invokeAdhoc(promptTemplate: String, fieldText: String): Flow<Event> = flow {
        if (fieldText.isBlank()) {
            emit(Event.Error("No text in field.")); return@flow
        }
        val prompt = promptTemplate.replace(Task.PLACEHOLDER, fieldText)
        runPrompt(prompt).collect(::emit)
    }

    fun invokeRaw(prompt: String): Flow<Event> = flow {
        if (prompt.isBlank()) {
            emit(Event.Error("No text in field.")); return@flow
        }
        runPrompt(prompt).collect(::emit)
    }

    private fun runPrompt(prompt: String): Flow<Event> {
        var handle: ResidencyHandle? = null
        return flow {
        val spec = resolveActiveModel() as? ChatModelSpec ?: run {
            emit(Event.Error("No model downloaded. Open Aide app and download one."))
            return@flow
        }
        if (engineRepo.loadedModelId != spec.id) emit(Event.Warming(spec.displayName))
        try {
            handle = acquireModel(spec, null, owner = Surface.IME)
        } catch (t: Throwable) {
            emit(Event.Error("Model load failed: ${t.message ?: t::class.simpleName}"))
            return@flow
        }
        val buffer = StringBuilder()
        try {
            engineRepo.engineGenerate(BREVITY_PREAMBLE + prompt).collect { delta ->
                buffer.append(delta)
                emit(Event.Streaming(buffer.toString()))
            }
        } catch (t: Throwable) {
            emit(Event.Error("Generation failed: ${t.message ?: t::class.simpleName}"))
            return@flow
        }
        val finalText = buffer.toString().trim()
        if (finalText.isEmpty()) {
            emit(Event.Error("Model returned empty response."))
            return@flow
        }
        emit(Event.Done(finalText))
        }.onCompletion { handle?.release(ResidencyDurations.IME_KEEPALIVE_MS) }
    }

    companion object {
        // Universal brevity contract applied to every task (built-in, custom, adhoc, raw).
        // Output surface is an IME suggestion bar — long-winded responses make the
        // keyboard feel slow and the bar overflow.
        private val BREVITY_PREAMBLE = """
            You are powering an on-keyboard text assistant. Responses are inserted
            directly into a text field, so keep them short and to the point. Match
            the length and register of the input — do not pad, do not explain your
            answer, and do not add a preamble or sign-off. Unless the task below
            explicitly asks for a list or multiple items, return a single answer:
            no alternatives, no variants, no "Option 1 / Option 2" menus, no
            follow-up questions.

        """.trimIndent()
    }
}
