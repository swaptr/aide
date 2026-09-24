package com.sabreware.aide.core.domain.permission

/**
 * Standardized outcome of a high-level [RuntimePermissionGate.ensure] call. Carries catalog-sourced
 * copy so callers stop hardcoding denial strings and branching on permanent-vs-transient.
 */
sealed interface PermissionResult {
    data object Granted : PermissionResult

    /** Denied but re-askable. [message] is the catalog's transient-denied copy. */
    data class Denied(val message: String) : PermissionResult

    /** Permanently denied — the system won't prompt again; user must visit Settings. */
    data class PermanentlyDenied(val message: String) : PermissionResult

    val isGranted: Boolean get() = this is Granted

    /** The denial copy for a denied result (transient or permanent); null when granted. */
    val deniedMessage: String?
        get() = when (this) {
            Granted -> null
            is Denied -> message
            is PermanentlyDenied -> message
        }
}
