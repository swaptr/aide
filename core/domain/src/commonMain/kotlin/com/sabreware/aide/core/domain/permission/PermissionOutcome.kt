package com.sabreware.aide.core.domain.permission

// shouldShowRationale is false after "Don't allow" + "Don't ask again" — callers
// map this to a "go to Settings" deep-link instead of looping the dialog.
data class PermissionOutcome(
    val permission: String,
    val granted: Boolean,
    val shouldShowRationale: Boolean,
) {
    val permanentlyDenied: Boolean get() = !granted && !shouldShowRationale
}

data class MultiPermissionOutcome(
    val perPermission: Map<String, PermissionOutcome>,
) {
    val allGranted: Boolean get() = perPermission.values.all { it.granted }

    val anyPermanentlyDenied: Boolean get() = perPermission.values.any { it.permanentlyDenied }

    fun granted(permission: String): Boolean =
        perPermission[permission]?.granted ?: false

    companion object {
        fun synthesizeGranted(permissions: Collection<String>): MultiPermissionOutcome =
            MultiPermissionOutcome(
                permissions.associateWith {
                    PermissionOutcome(it, granted = true, shouldShowRationale = false)
                },
            )

        /**
         * The outcome for a request that never came back — the host was destroyed, or the deadline expired.
         * Deliberately NOT permanently denied ([shouldShowRationale] stays true): nothing was decided, so the
         * caller should be able to ask again rather than being sent to a Settings page it cannot fix.
         */
        fun synthesizeAbandoned(permissions: Collection<String>): MultiPermissionOutcome =
            MultiPermissionOutcome(
                permissions.associateWith {
                    PermissionOutcome(it, granted = false, shouldShowRationale = true)
                },
            )
    }
}
