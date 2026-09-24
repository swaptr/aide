package com.sabreware.aide.platform.android.permission

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.sabreware.aide.core.domain.permission.AppPermission
import com.sabreware.aide.core.domain.permission.MultiPermissionOutcome
import com.sabreware.aide.core.domain.permission.PermissionOutcome
import com.sabreware.aide.core.domain.permission.PermissionResult
import com.sabreware.aide.core.domain.permission.RuntimePermissionGate
import com.sabreware.aide.core.domain.permission.SpecialPermission
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "RuntimePermissionGate"

/**
 * How long an unanswered request may hold its awaiter. This is a backstop, not a UX deadline: the
 * trampoline publishes on every path it can reach, including `onDestroy`, so the only way to reach this
 * is a host that died without running any of them. Generous, because a *special* permission sends the
 * user to a Settings page they may take their time on.
 */
private const val AWAIT_DEADLINE_MS = 5 * 60 * 1000L

// One [CompletableDeferred] per in-flight request, registered BEFORE the trampoline is launched.
// Android impl of the commonMain [RuntimePermissionGate] interface; keeps the android-only extras
// (requestAll/request with an Intent launcher, publish) the trampoline + assistant use directly.
class AndroidRuntimePermissionGate(
    private val appContext: Context,
    private val trampoline: PermissionTrampoline,
) : RuntimePermissionGate {

    /**
     * In-flight requests, keyed by request id. A per-request slot rather than a shared stream, for two
     * reasons the old `MutableSharedFlow(replay = 0)` + `tryEmit` could not give:
     *
     *  - **No lost publish.** The slot is registered before the trampoline starts, so an answer that beats
     *    the awaiter into place is still delivered. A replay-0 emission that arrived before the collector
     *    subscribed was dropped, and the awaiter then waited forever.
     *  - **No starvation.** A cancelled awaiter removes only its own slot; concurrent surfaces are
     *    untouched (the property the shared flow was chosen for).
     */
    private val pending = ConcurrentHashMap<String, CompletableDeferred<MultiPermissionOutcome>>()

    fun isGranted(permission: String): Boolean = ContextCompat.checkSelfPermission(
        appContext,
        permission,
    ) == PackageManager.PERMISSION_GRANTED

    fun areAllGranted(permissions: Collection<String>): Boolean =
        permissions.all { isGranted(it) }

    // --- High-level catalog API: the single surface every feature should use. ---

    /** SDK-aware granted check. Units that don't exist on the running OS are treated as granted. */
    override fun isGranted(permission: AppPermission): Boolean =
        permission.isImplicitlyGranted || areAllGranted(permission.manifestPermissions)

    /** Granted check for the runtime permission backing a tool category (true if it needs none). */
    override fun isGranted(special: SpecialPermission): Boolean = special.androidIsGranted()

    override suspend fun ensure(permission: AppPermission, showRationale: Boolean): PermissionResult =
        ensure(permission, showRationale, launchActivity = null)

    override suspend fun ensureSpecial(special: SpecialPermission): PermissionResult =
        ensureSpecial(special, launchActivity = null)

    /**
     * Check-then-request a catalog permission, returning a [PermissionResult] with catalog-sourced
     * messages — so callers never branch on permanent-vs-transient or hardcode denial strings. The
     * trampoline handles the in-app rationale (when [showRationale]) and the system dialog.
     * [launchActivity] has NO default so this overload stays distinct from the 2-arg interface method
     * (overlay surfaces pass a custom launcher; everyone else uses the interface `ensure`).
     */
    suspend fun ensure(
        permission: AppPermission,
        showRationale: Boolean = true,
        launchActivity: ((Intent) -> Unit)?,
    ): PermissionResult {
        if (isGranted(permission)) return PermissionResult.Granted
        val outcome = requestAll(permission.manifestPermissions, showRationale, launchActivity)
        return when {
            outcome.allGranted -> PermissionResult.Granted
            outcome.anyPermanentlyDenied ->
                PermissionResult.PermanentlyDenied(permission.permanentlyDeniedMessage)
            else -> PermissionResult.Denied(permission.transientDeniedMessage)
        }
    }

    /**
     * Check-then-request a [SpecialPermission] (granted from a Settings page, not the runtime dialog).
     * Routes through the same trampoline so the user sees the in-app rationale first — exactly like
     * [ensure] for runtime permissions — then the Settings page.
     */
    suspend fun ensureSpecial(
        special: SpecialPermission,
        launchActivity: ((Intent) -> Unit)?,
    ): PermissionResult {
        if (special.androidIsGranted()) return PermissionResult.Granted
        val id = UUID.randomUUID().toString()
        val intent = trampoline.intent().apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(PermissionTrampoline.Extras.REQUEST_ID, id)
            putExtra(PermissionTrampoline.Extras.SPECIAL, special.name)
        }
        val outcome = awaitOutcome(id, listOf(special.name)) {
            if (launchActivity != null) launchActivity(intent) else appContext.startActivity(intent)
        }
        return if (outcome.allGranted) {
            PermissionResult.Granted
        } else {
            PermissionResult.Denied(special.transientDeniedMessage)
        }
    }

    /**
     * @param showRationale when true (default) the trampoline may show the in-app "why we need this"
     * dialog — but only when the OS warrants a rationale (after a prior denial,
     * shouldShowRequestPermissionRationale). First asks go straight to the system dialog. Pass false
     * to suppress the custom dialog entirely (e.g. the caller's own UI already explained).
     */
    suspend fun request(permission: String, showRationale: Boolean = true): PermissionOutcome =
        requestAll(listOf(permission), showRationale).perPermission.getValue(permission)

    /**
     * @param launchActivity optional custom launcher for the trampoline. Non-Activity surfaces whose
     * window outranks a plain activity (e.g. the assistant's TYPE_VOICE_INTERACTION overlay) pass one
     * — e.g. `VoiceInteractionSession::startAssistantActivity` — so the system dialog foregrounds
     * above them. Default `null` uses [Context.startActivity], correct for Activity-hosted callers.
     */
    suspend fun requestAll(
        permissions: List<String>,
        showRationale: Boolean = true,
        launchActivity: ((Intent) -> Unit)? = null,
    ): MultiPermissionOutcome {
        if (permissions.isEmpty()) return MultiPermissionOutcome(emptyMap())
        // Android silently denies undeclared permissions with no dialog — log a
        // warning so a missed manifest entry surfaces as a diagnostic, not a no-op.
        permissions.forEach { perm ->
            if (perm !in declaredRuntimePermissions()) {
                Log.w(
                    TAG,
                    "Permission '$perm' is not declared in AndroidManifest.xml; " +
                        "Android will silently deny without showing the system dialog.",
                )
            }
        }
        if (areAllGranted(permissions)) {
            return MultiPermissionOutcome.synthesizeGranted(permissions)
        }
        val id = UUID.randomUUID().toString()
        val intent = trampoline.intent().apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(PermissionTrampoline.Extras.PERMISSIONS, permissions.toTypedArray())
            putExtra(PermissionTrampoline.Extras.REQUEST_ID, id)
            putExtra(PermissionTrampoline.Extras.SHOW_RATIONALE, showRationale)
        }
        return awaitOutcome(id, permissions) {
            if (launchActivity != null) launchActivity(intent) else appContext.startActivity(intent)
        }
    }

    /**
     * Register the slot, start the trampoline, then wait — in that order, so the answer cannot arrive
     * before there is anywhere to put it. Always resolves: a host that dies without publishing hits the
     * deadline and the caller gets an "abandoned" outcome instead of suspending for the life of the
     * process. That mattered because callers hold locks across this: the dictation controller awaited it
     * inside its own mutex, so one lost publish used to deadlock every later toggle on every surface.
     */
    private suspend fun awaitOutcome(
        id: String,
        permissions: Collection<String>,
        launch: () -> Unit,
    ): MultiPermissionOutcome {
        val slot = CompletableDeferred<MultiPermissionOutcome>()
        pending[id] = slot
        return try {
            launch()
            withTimeoutOrNull(AWAIT_DEADLINE_MS) { slot.await() } ?: run {
                Log.w(TAG, "permission request $id was never answered after ${AWAIT_DEADLINE_MS}ms")
                MultiPermissionOutcome.synthesizeAbandoned(permissions)
            }
        } finally {
            pending.remove(id)
        }
    }

    /** Called by the trampoline (a different module now) when the user has answered. */
    fun publish(requestId: String, outcome: MultiPermissionOutcome) {
        pending.remove(requestId)?.complete(outcome)
    }

    private val declaredPermsLazy: Set<String> by lazy {
        @Suppress("DEPRECATION")
        val info = appContext.packageManager.getPackageInfo(
            appContext.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        info.requestedPermissions?.toSet().orEmpty()
    }

    private fun declaredRuntimePermissions(): Set<String> = declaredPermsLazy
}
