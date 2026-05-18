package com.swaptr.aide.permission

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
    }
}
