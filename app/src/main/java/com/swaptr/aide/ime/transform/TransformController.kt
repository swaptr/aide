package com.swaptr.aide.ime.transform

import android.view.inputmethod.InputConnection
import com.swaptr.aide.data.custom.CustomInstructionRepository
import com.swaptr.aide.data.model.LlmEngineRepository
import com.swaptr.aide.data.model.ModelGateState
import com.swaptr.aide.data.model.ModelRegistryRepository
import com.swaptr.aide.data.task.GroupChipItem
import com.swaptr.aide.data.task.TaskRepository
import com.swaptr.aide.data.task.TaskRepository.Companion.PLACEHOLDER
import com.swaptr.aide.di.ApplicationScope
import com.swaptr.aide.domain.usecase.RunTaskUseCase
import com.swaptr.aide.ime.text.TextContextRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class TransformController @Inject constructor(
    private val textContext: TextContextRepository,
    private val engineRepo: LlmEngineRepository,
    taskRepo: TaskRepository,
    private val runTaskUseCase: RunTaskUseCase,
    private val customRepo: CustomInstructionRepository,
    private val registryRepo: ModelRegistryRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    sealed interface Mode {
        data object Idle : Mode
        data object Loading : Mode
        data object Generating : Mode
        data object Ready : Mode
        data class Error(val message: String) : Mode
        data object NoModel : Mode
        data class DownloadingModel(val progress: Float) : Mode
    }

    data class ChainStep(
        val taskId: String,
        val taskName: String,
        val output: String,
        val adhocPrompt: String? = null,
    )
    data class Preview(
        val taskId: String,
        val taskName: String,
        val output: String,
        val isFinal: Boolean,
        val adhocPrompt: String? = null,
    )

    enum class AdhocIntent { APPLY, QUEUE }

    sealed interface RedoEntry {
        data class PoppedPreview(val preview: Preview) : RedoEntry
        data class PoppedStep(val step: ChainStep) : RedoEntry
    }

    private val _mode = MutableStateFlow<Mode>(Mode.Idle)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _preview = MutableStateFlow<Preview?>(null)
    val preview: StateFlow<Preview?> = _preview.asStateFlow()

    private val _chain = MutableStateFlow<List<ChainStep>>(emptyList())
    val chain: StateFlow<List<ChainStep>> = _chain.asStateFlow()

    val chips: StateFlow<List<GroupChipItem>> = taskRepo.observeChipItems().stateIn(
        appScope,
        SharingStarted.Eagerly,
        emptyList(),
    )

    val gateState: StateFlow<ModelGateState> get() = registryRepo.gateStateFlow

    // Buffered (don't lose pre-subscribe taps) but non-replaying (re-attach mustn't reopen).
    private val _requestCustomEditor = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val requestCustomEditor: SharedFlow<Unit> = _requestCustomEditor.asSharedFlow()

    // KeyboardPage flips false so subpage editor's header doesn't double up with shared bar.
    private val _headerVisible = MutableStateFlow(true)
    val headerVisible: StateFlow<Boolean> = _headerVisible.asStateFlow()
    fun setHeaderVisible(visible: Boolean) { _headerVisible.value = visible }

    private val _fieldSensitive = MutableStateFlow(false)
    val fieldSensitive: StateFlow<Boolean> = _fieldSensitive.asStateFlow()
    fun setFieldSensitive(sensitive: Boolean) { _fieldSensitive.value = sensitive }

    private val redoStack: MutableList<RedoEntry> = mutableListOf()
    private var originalFieldText: String? = null
    private var generationJob: Job? = null
    private val generationMutex: Mutex = Mutex()
    private var pendingRelease: Job? = null

    val isStreaming: Boolean
        get() = _mode.value is Mode.Loading || _mode.value is Mode.Generating

    init {
        // Prime mode from the gate snapshot so the first observer doesn't see
        // Idle if a model isn't installed yet.
        val initial = registryRepo.gateStateFlow.value
        if (initial !is ModelGateState.Ready) _mode.value = modeForGate(initial)

        appScope.launch {
            registryRepo.gateStateFlow.collect(::onGateStateChanged)
        }
        appScope.launch {
            customRepo.pending.collect { instruction ->
                instruction ?: return@collect
                customRepo.consume()
                runAdhocInstruction(instruction, AdhocIntent.APPLY)
            }
        }
    }

    fun requestCustomInstructionsEditor() {
        _requestCustomEditor.tryEmit(Unit)
    }

    // intent is informational; popup mediates the final commit for both Apply and Queue paths.
    fun runAdhocInstruction(text: String, @Suppress("UNUSED_PARAMETER") intent: AdhocIntent) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        when (_mode.value) {
            Mode.Loading, Mode.Generating -> {
                generationJob?.cancel()
                _preview.value = null
                _mode.value = Mode.Idle
                return
            }
            else -> Unit
        }
        val template = buildAdhocTemplate(trimmed)
        startAdhoc(template, trimmed)
    }

    private fun buildAdhocTemplate(instruction: String): String =
        "$instruction Output only the result, with no preamble, no quotation " +
            "marks, no commentary.\n\nText:\n$PLACEHOLDER\n\nResult:"

    // Sends field text as raw prompt (no template wrap); popup mediates apply.
    fun runRawPrompt() {
        when (_mode.value) {
            Mode.Loading, Mode.Generating -> {
                generationJob?.cancel()
                _preview.value = null
                _mode.value = Mode.Idle
                return
            }
            else -> Unit
        }
        cancelPendingRelease()
        if (!checkModelGate()) return
        redoStack.clear()
        val source = sourceTextForNextRun()
        if (source.isBlank()) {
            _mode.value = Mode.Error("No text in field.")
            return
        }
        generationJob?.cancel()
        val displayName = source.take(40).let {
            if (source.length > 40) "$it…" else it
        }
        _preview.value = Preview(
            taskId = RAW_TASK_ID,
            taskName = displayName,
            output = "",
            isFinal = false,
        )
        _mode.value = Mode.Loading
        generationJob = appScope.launch {
            generationMutex.withLock {
                runTaskUseCase.invokeRaw(source).collect { event ->
                    when (event) {
                        is RunTaskUseCase.Event.Warming -> _mode.value = Mode.Loading
                        is RunTaskUseCase.Event.Streaming -> {
                            if (_mode.value != Mode.Generating) _mode.value = Mode.Generating
                            _preview.value = _preview.value?.copy(output = event.text)
                        }
                        is RunTaskUseCase.Event.Done -> {
                            _preview.value = _preview.value?.copy(
                                output = event.text,
                                isFinal = true,
                            )
                            _mode.value = Mode.Ready
                        }
                        is RunTaskUseCase.Event.Error -> {
                            _preview.value = null
                            _mode.value = Mode.Error(event.message)
                        }
                    }
                }
            }
        }
    }

    private fun startAdhoc(template: String, instructionText: String) {
        cancelPendingRelease()
        if (!checkModelGate()) return
        redoStack.clear()
        val source = sourceTextForNextRun()
        if (source.isBlank()) {
            _mode.value = Mode.Error("No text in field.")
            return
        }
        generationJob?.cancel()
        val displayName = instructionText.take(40).let {
            if (instructionText.length > 40) "$it…" else it
        }
        _preview.value = Preview(
            taskId = ADHOC_TASK_ID,
            taskName = displayName,
            output = "",
            isFinal = false,
            adhocPrompt = template,
        )
        _mode.value = Mode.Loading
        generationJob = appScope.launch {
            generationMutex.withLock {
                runTaskUseCase.invokeAdhoc(template, source).collect { event ->
                    when (event) {
                        is RunTaskUseCase.Event.Warming -> _mode.value = Mode.Loading
                        is RunTaskUseCase.Event.Streaming -> {
                            if (_mode.value != Mode.Generating) _mode.value = Mode.Generating
                            _preview.value = _preview.value?.copy(output = event.text)
                        }
                        is RunTaskUseCase.Event.Done -> {
                            _preview.value = _preview.value?.copy(
                                output = event.text,
                                isFinal = true,
                            )
                            _mode.value = Mode.Ready
                        }
                        is RunTaskUseCase.Event.Error -> {
                            _preview.value = null
                            _mode.value = Mode.Error(event.message)
                        }
                    }
                }
            }
        }
    }

    // Tap-while-running cancels — preserves original "tap chip again to cancel" UX.
    fun onChipTapped(taskId: String, taskName: String) {
        when (_mode.value) {
            Mode.Loading, Mode.Generating -> {
                generationJob?.cancel()
                _preview.value = null
                _mode.value = Mode.Idle
                return
            }
            else -> Unit
        }
        startTask(taskId, taskName)
    }

    private fun startTask(taskId: String, taskName: String) {
        cancelPendingRelease()
        if (!checkModelGate()) return
        redoStack.clear()
        val source = sourceTextForNextRun()
        if (source.isBlank()) {
            _mode.value = Mode.Error("No text in field.")
            return
        }
        generationJob?.cancel()
        _preview.value = Preview(taskId, taskName, "", isFinal = false)
        _mode.value = Mode.Loading
        generationJob = appScope.launch {
            generationMutex.withLock {
                runTaskUseCase.invoke(taskId, source).collect { event ->
                    when (event) {
                        is RunTaskUseCase.Event.Warming -> _mode.value = Mode.Loading
                        is RunTaskUseCase.Event.Streaming -> {
                            if (_mode.value != Mode.Generating) _mode.value = Mode.Generating
                            _preview.value = _preview.value?.copy(output = event.text)
                        }
                        is RunTaskUseCase.Event.Done -> {
                            _preview.value = _preview.value?.copy(
                                output = event.text,
                                isFinal = true,
                            )
                            _mode.value = Mode.Ready
                        }
                        is RunTaskUseCase.Event.Error -> {
                            _preview.value = null
                            _mode.value = Mode.Error(event.message)
                        }
                    }
                }
            }
        }
    }

    private fun sourceTextForNextRun(): String {
        _chain.value.lastOrNull()?.let { return it.output }
        originalFieldText?.let { return it }
        val current = textContext.fetchFull()?.text?.toString()
            ?: textContext.fetchFullAggressive()?.text?.toString()
            ?: ""
        originalFieldText = current
        return current
    }

    fun addPreviewToChain() {
        val p = _preview.value ?: return
        if (!p.isFinal) return
        _chain.value = _chain.value + ChainStep(p.taskId, p.taskName, p.output, p.adhocPrompt)
        _preview.value = null
        redoStack.clear()
        _mode.value = Mode.Idle
    }

    fun undo() {
        when {
            _preview.value != null -> {
                val p = _preview.value!!
                generationJob?.cancel()
                if (p.isFinal) redoStack += RedoEntry.PoppedPreview(p)
                _preview.value = null
                _mode.value = Mode.Idle
            }
            _chain.value.isNotEmpty() -> {
                val list = _chain.value.toMutableList()
                val popped = list.removeAt(list.size - 1)
                _chain.value = list
                redoStack += RedoEntry.PoppedStep(popped)
            }
            else -> return
        }
    }

    fun redo() {
        val entry = redoStack.removeLastOrNull() ?: return
        when (entry) {
            is RedoEntry.PoppedPreview -> {
                _preview.value = entry.preview
                _mode.value = Mode.Ready
            }
            is RedoEntry.PoppedStep -> {
                _chain.value = _chain.value + entry.step
            }
        }
    }

    fun canUndo(): Boolean = _preview.value != null || _chain.value.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()
    fun canApply(): Boolean = _preview.value?.isFinal == true || _chain.value.isNotEmpty()
    fun canAdd(): Boolean = _preview.value?.isFinal == true

    fun removeChainStep(index: Int) {
        val list = _chain.value.toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        _chain.value = list
        redoStack.clear()
    }

    fun pendingCount(): Int = _chain.value.size

    // false if nothing applicable, connection gone, or field sensitive.
    fun applyToField(ic: InputConnection?): Boolean {
        val finalText = _preview.value?.takeIf { it.isFinal }?.output
            ?: _chain.value.lastOrNull()?.output
            ?: return false
        val conn = ic ?: run {
            _mode.value = Mode.Error("Input connection lost.")
            return false
        }
        if (textContext.context.value.isSensitive) return false
        conn.beginBatchEdit()
        try {
            conn.performContextMenuAction(android.R.id.selectAll)
            conn.commitText(finalText, 1)
        } finally {
            conn.endBatchEdit()
        }
        resetSession()
        return true
    }

    fun discardPreview() {
        generationJob?.cancel()
        _preview.value = null
        _mode.value = Mode.Idle
    }

    fun resetSession() {
        _chain.value = emptyList()
        _preview.value = null
        redoStack.clear()
        originalFieldText = null
        _mode.value = modeForGate(registryRepo.gateStateFlow.value)
    }

    fun reapplyQueue() {
        if (_chain.value.isEmpty()) return
        cancelPendingRelease()
        if (!checkModelGate()) return
        redoStack.clear()
        val origin = originalFieldText
            ?: textContext.fetchFull()?.text?.toString()
            ?: textContext.fetchFullAggressive()?.text?.toString()
            ?: ""
        if (origin.isBlank()) {
            _mode.value = Mode.Error("No text in field.")
            return
        }
        originalFieldText = origin
        val steps = _chain.value.toList()
        _chain.value = emptyList()
        _preview.value = null
        generationJob?.cancel()
        _mode.value = Mode.Loading
        generationJob = appScope.launch {
            generationMutex.withLock {
                var input = origin
                steps.forEachIndexed { idx, step ->
                    val buffer = StringBuilder()
                    var failed: String? = null
                    val runFlow = step.adhocPrompt
                        ?.let { runTaskUseCase.invokeAdhoc(it, input) }
                        ?: runTaskUseCase.invoke(step.taskId, input)
                    runFlow.collect { event ->
                        when (event) {
                            is RunTaskUseCase.Event.Streaming -> {
                                buffer.clear(); buffer.append(event.text)
                                if (_mode.value != Mode.Generating) _mode.value = Mode.Generating
                                // Surface progress through the preview slot so observers (popup,
                                // breadcrumb) animate during a re-run.
                                _preview.value = Preview(
                                    taskId = step.taskId,
                                    taskName = "Step ${idx + 1}/${steps.size}: ${step.taskName}",
                                    output = event.text,
                                    isFinal = false,
                                )
                            }
                            is RunTaskUseCase.Event.Done -> {
                                buffer.clear(); buffer.append(event.text)
                            }
                            is RunTaskUseCase.Event.Error -> failed = event.message
                            else -> Unit
                        }
                    }
                    if (failed != null) {
                        _mode.value = Mode.Error("Step ${idx + 1} (${step.taskName}): $failed")
                        return@withLock
                    }
                    val finalText = buffer.toString().trim()
                    _chain.value = _chain.value + ChainStep(step.taskId, step.taskName, finalText)
                    input = finalText
                }
                _chain.value.lastOrNull()?.let { last ->
                    val list = _chain.value.toMutableList()
                    list.removeAt(list.size - 1)
                    _chain.value = list
                    _preview.value = Preview(last.taskId, last.taskName, last.output, isFinal = true)
                    _mode.value = Mode.Ready
                }
            }
        }
    }

    fun onWindowShown() {
        cancelPendingRelease()
    }

    fun onWindowHidden() {
        schedulePendingRelease()
    }

    fun onFinishInput() {
        schedulePendingRelease()
    }

    // Drop cached chain/preview so stale state doesn't carry across an editor switch.
    fun onStartInput() {
        cancelPendingRelease()
        resetSession()
    }

    private fun schedulePendingRelease() {
        pendingRelease?.cancel()
        pendingRelease = appScope.launch {
            delay(RELEASE_DELAY_MS)
            generationJob?.cancel()
            engineRepo.lifecycleLock.withLock {
                if (engineRepo.loadedModelId != null) {
                    withContext(Dispatchers.IO) { engineRepo.unload() }
                }
            }
        }
    }

    private fun cancelPendingRelease() {
        pendingRelease?.cancel()
        pendingRelease = null
    }

    private fun checkModelGate(): Boolean = when (val g = registryRepo.gateStateFlow.value) {
        is ModelGateState.Ready -> true
        is ModelGateState.Downloading -> {
            generationJob?.cancel()
            _preview.value = null
            _mode.value = Mode.DownloadingModel(g.progress)
            false
        }
        ModelGateState.NoModel -> {
            generationJob?.cancel()
            _preview.value = null
            _mode.value = Mode.NoModel
            false
        }
    }

    private fun onGateStateChanged(state: ModelGateState) {
        when (_mode.value) {
            Mode.NoModel -> when (state) {
                is ModelGateState.Ready -> _mode.value = Mode.Idle
                is ModelGateState.Downloading -> _mode.value = Mode.DownloadingModel(state.progress)
                ModelGateState.NoModel -> Unit
            }
            is Mode.DownloadingModel -> when (state) {
                is ModelGateState.Ready -> _mode.value = Mode.Idle
                is ModelGateState.Downloading -> _mode.value = Mode.DownloadingModel(state.progress)
                ModelGateState.NoModel -> _mode.value = Mode.NoModel
            }
            else -> Unit
        }
    }

    private fun modeForGate(state: ModelGateState): Mode = when (state) {
        is ModelGateState.Ready -> Mode.Idle
        is ModelGateState.Downloading -> Mode.DownloadingModel(state.progress)
        ModelGateState.NoModel -> Mode.NoModel
    }

    private companion object {
        const val RELEASE_DELAY_MS = 1_500L
        const val ADHOC_TASK_ID = "adhoc"
        const val RAW_TASK_ID = "raw"
    }
}
