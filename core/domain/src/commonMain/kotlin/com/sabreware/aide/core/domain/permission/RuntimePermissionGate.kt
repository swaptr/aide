package com.sabreware.aide.core.domain.permission


/**
 * The high-level permission gate the UI + features use — the single surface for check-then-request.
 * Platform impl (Android trampoline via `RuntimePermissionActivity`) lives in `:app`
 * (`AndroidRuntimePermissionGate`). Callers get [PermissionResult]s with catalog-sourced copy so they
 * never branch on permanent-vs-transient or hardcode denial strings.
 */
interface RuntimePermissionGate {

    /** SDK-aware granted check. Units that don't exist on the running OS are treated as granted. */
    fun isGranted(permission: AppPermission): Boolean

    /** Granted check for a special (Settings-page) permission. */
    fun isGranted(special: SpecialPermission): Boolean

    /** Check-then-request a catalog runtime permission, showing the in-app rationale when warranted. */
    suspend fun ensure(permission: AppPermission, showRationale: Boolean = true): PermissionResult

    /** Check-then-request a [SpecialPermission] (granted from a Settings page, not the runtime dialog). */
    suspend fun ensureSpecial(special: SpecialPermission): PermissionResult
}
