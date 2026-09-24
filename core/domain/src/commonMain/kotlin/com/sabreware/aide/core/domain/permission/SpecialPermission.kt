package com.sabreware.aide.core.domain.permission

/**
 * Special / app-op permissions that are granted from a system Settings page rather than the runtime
 * dialog (e.g. All files access / MANAGE_EXTERNAL_STORAGE). Kept separate from [AppPermission] (runtime
 * permissions) but routed through the same [RuntimePermissionGate] so they share the in-app rationale UI.
 *
 * Pure identity + copy here (commonMain). The platform-specific granted-check + Settings intent live in
 * the Android [RuntimePermissionGate] impl (`SpecialPermission.androidIsGranted()` / `.settingsIntent()`).
 */
enum class SpecialPermission(
    val rationaleTitle: String,
    val rationaleMessage: String,
    /** Shown when the user leaves the Settings page without granting. */
    val transientDeniedMessage: String,
) {
    ALL_FILES_ACCESS(
        rationaleTitle = "Allow file access",
        rationaleMessage =
            "Aide needs all-files access to list, read, and modify files in the folders you grant.",
        transientDeniedMessage = "All files access is required for filesystem tools.",
    ),
}
