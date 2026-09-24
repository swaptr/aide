package com.sabreware.aide.core.designsystem.state

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * THE canonical async-state model for a UI surface. Every screen/section that loads something models it as
 * `UiState<T>` (not a nullable, not a bare boolean) so loading/failure handling is uniform in code and — via
 * [com.sabreware.aide.core.designsystem.state.StatePane] and the skeleton primitives — uniform on screen.
 *
 * - [Loading] — first resolution in flight. Render a skeleton (list-shaped surfaces) or [StatePane]'s
 *   spinner (full panes). Never render "empty" copy while loading.
 * - [Ready] — value available. `Ready(emptyList())` IS the empty state — branch to `Placeholder` there.
 * - [Failed] — resolution broke. Render the message via `AppNotice` / [StatePane]'s failed pane.
 *
 * Produce it with [stateInUi] in a ViewModel: `observeThings().stateInUi(viewModelScope)`. The upstream
 * flow stays cold until the consuming component actually composes, so data resolves at the component,
 * on request — never at app root/startup.
 */
@Immutable
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Ready<T>(val value: T) : UiState<T>
    data class Failed(val message: String) : UiState<Nothing>
}

val <T> UiState<T>.valueOrNull: T? get() = (this as? UiState.Ready<T>)?.value
val UiState<*>.isLoading: Boolean get() = this is UiState.Loading

/** Transforms the Ready payload; Loading/Failed pass through — for deriving row/view models. */
inline fun <T, R> UiState<T>.map(transform: (T) -> R): UiState<R> = when (this) {
    is UiState.Ready -> UiState.Ready(transform(value))
    UiState.Loading -> UiState.Loading
    is UiState.Failed -> this
}

/** Wraps a data flow's emissions in [UiState.Ready]; a thrown upstream error becomes [UiState.Failed]. */
fun <T> Flow<T>.asUiState(
    failedMessage: (Throwable) -> String = { it.message ?: "Something went wrong." },
): Flow<UiState<T>> = map<T, UiState<T>> { UiState.Ready(it) }
    .catch { emit(UiState.Failed(failedMessage(it))) }

/**
 * The standard ViewModel shape: `Loading` until the first emission, sharing stops 5s after the last
 * collector leaves (so a closed surface releases its query; reopening within 5s replays instantly).
 */
fun <T> Flow<T>.stateInUi(
    scope: CoroutineScope,
    started: SharingStarted = SharingStarted.WhileSubscribed(5_000),
): StateFlow<UiState<T>> = asUiState().stateIn(scope, started, UiState.Loading)

/**
 * [stateInUi] over a data-layer cache: a repository that maintains its snapshot app-wide exposes
 * `StateFlow<T?>` (`stateIn(appScope, WhileSubscribed(5s), null)` — null = never resolved this session),
 * and this seeds the surface SYNCHRONOUSLY from [StateFlow.value]. A surface whose data already resolved
 * once re-opens straight into [UiState.Ready] — no skeleton frame, no crossfade — while the shared
 * upstream refreshes behind it (stale-while-revalidate). A first-ever open still starts at [Loading].
 *
 * Named, not an overload: `StateFlow` is covariant, so a `StateFlow<T>` also matches a
 * `StateFlow<T?>` receiver and an overload would silently capture existing [stateInUi] call sites.
 */
fun <T : Any> StateFlow<T?>.stateInUiCached(scope: CoroutineScope): StateFlow<UiState<T>> =
    filterNotNull().asUiState().stateIn(
        scope,
        SharingStarted.WhileSubscribed(5_000),
        value?.let { UiState.Ready(it) } ?: UiState.Loading,
    )

/**
 * [stateInUiCached] for a flow DERIVED from a data-layer cache rather than the cache itself — e.g. a
 * refresh trigger `flatMapLatest`-ing over it. The caller passes the cache's current value as [seed]:
 * `Ready(seed)` when warm, [UiState.Loading] otherwise; same synchronous-first-frame guarantee.
 */
fun <T> Flow<T>.stateInUiSeeded(scope: CoroutineScope, seed: T?): StateFlow<UiState<T>> =
    asUiState().stateIn(
        scope,
        SharingStarted.WhileSubscribed(5_000),
        seed?.let { UiState.Ready(it) } ?: UiState.Loading,
    )

/**
 * The same guarantee for a surface whose state is its **own** data class rather than [UiState].
 *
 * The guarantee is the point: a bare `stateIn` lets an upstream throw propagate into the sharing coroutine
 * and cancel it, so the StateFlow holds its seed for the rest of the ViewModel's life — no value, no error,
 * no retry, and nothing on screen to say so. That is a silent permanent skeleton, and it was the shape of
 * every hand-rolled `stateIn` in the app.
 *
 * [onError] maps the failure into the surface's own state (usually `seed.copy(error = …)` or
 * `current.copy(error = …)`), so the screen shows what it has plus a notice.
 *
 * Prefer plain [stateInUi] for a surface that is genuinely "loading one thing" — this exists for the
 * composite screens whose state carries filters, selection and transient flags alongside the data.
 */
fun <T> Flow<T>.stateInUi(
    scope: CoroutineScope,
    initial: T,
    started: SharingStarted = SharingStarted.WhileSubscribed(5_000),
    onError: (Throwable) -> T,
): StateFlow<T> = catch { emit(onError(it)) }.stateIn(scope, started, initial)
