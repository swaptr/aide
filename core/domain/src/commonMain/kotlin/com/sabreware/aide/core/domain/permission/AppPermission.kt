package com.sabreware.aide.core.domain.permission


/**
 * Single source of truth for every runtime permission the app requests. One entry per *logical
 * unit*; grouped units (calendar, contacts) bundle their read+write strings so one prompt covers
 * the feature.
 *
 * All facts that used to be scattered live here: the android permission string(s), min SDK,
 * rationale copy, user-facing denial messages, the LLM-facing tool-denied envelope text, and the
 * linkage to the toolset that needs it (a [com.sabreware.aide.core.domain.tools.Toolset] names its
 * [CategoryRequirement], which names one of these). No other file hardcodes a permission string or a
 * denial message.
 *
 * Boundary: special/appop permissions (e.g. MANAGE_EXTERNAL_STORAGE) are intentionally NOT modeled
 * here — they are granted via a Settings intent + `Environment.isExternalStorageManager()`, not the
 * runtime dialog. They surface as [CategoryRequirement.Special] and are handled in
 * ToolsSettingsViewModel/ToolsSettingsScreen.
 */
enum class AppPermission(
    /** Android permission string(s). More than one ⇒ a grouped unit requested together. */
    val manifestPermissions: List<String>,
    /** Below this SDK the permission does not exist and is treated as already granted. */
    val minSdk: Int,
    val rationaleTitle: String,
    val rationaleMessage: String,
    /** Shown when denied but still re-askable. */
    val transientDeniedMessage: String,
    /** Shown when permanently denied (must go to Settings). */
    val permanentlyDeniedMessage: String,
    /** LLM-facing envelope text for background tool checks (steers the model to ask the user). */
    val toolDeniedMessage: String,
) {
    MICROPHONE(
        manifestPermissions = listOf("android.permission.RECORD_AUDIO"),
        minSdk = 1,
        rationaleTitle = "Allow microphone",
        rationaleMessage =
            "Aide needs the microphone to hear your voice for dictation and the assistant.",
        transientDeniedMessage = "Microphone permission required.",
        permanentlyDeniedMessage =
            "Microphone disabled. Enable it in Settings → Apps → Aide → Permissions.",
        toolDeniedMessage = "missing microphone access — ask the user to grant it in Settings",
    ),
    CAMERA(
        manifestPermissions = listOf("android.permission.CAMERA"),
        minSdk = 1,
        rationaleTitle = "Allow camera",
        rationaleMessage = "Aide needs the camera to capture photos you send into a chat.",
        transientDeniedMessage = "Camera permission denied",
        permanentlyDeniedMessage =
            "Camera disabled. Enable it in Settings → Apps → Aide → Permissions.",
        toolDeniedMessage = "missing camera access — ask the user to grant it in Settings",
    ),
    NOTIFICATIONS(
        manifestPermissions = listOf("android.permission.POST_NOTIFICATIONS"),
        minSdk = 33, // TIRAMISU — below this, no permission exists (unreachable at minSdk 35).
        rationaleTitle = "Allow notifications",
        rationaleMessage =
            "Aide uses notifications to keep you posted on downloads and long-running tasks.",
        transientDeniedMessage = "Notifications are off. You can enable them later in Settings.",
        permanentlyDeniedMessage =
            "Notifications disabled. Enable them in Settings → Apps → Aide → Permissions.",
        toolDeniedMessage = "missing notification access — ask the user to grant it in Settings",
    ),
    CALENDAR(
        manifestPermissions = listOf(
            "android.permission.READ_CALENDAR",
            "android.permission.WRITE_CALENDAR",
        ),
        minSdk = 1,
        rationaleTitle = "Allow calendar",
        rationaleMessage = "Aide needs calendar access to read and manage your events.",
        transientDeniedMessage = "Calendar permission required.",
        permanentlyDeniedMessage =
            "Calendar disabled. Enable it in Settings → Apps → Aide → Permissions.",
        toolDeniedMessage = "missing calendar access — ask the user to grant calendar access in Settings",
    ),
    CONTACTS(
        manifestPermissions = listOf(
            "android.permission.READ_CONTACTS",
            "android.permission.WRITE_CONTACTS",
        ),
        minSdk = 1,
        rationaleTitle = "Allow contacts",
        rationaleMessage =
            "Aide needs contacts access to look up and update people you mention.",
        transientDeniedMessage = "Contacts permission required.",
        permanentlyDeniedMessage =
            "Contacts disabled. Enable it in Settings → Apps → Aide → Permissions.",
        toolDeniedMessage = "missing contacts access — ask the user to grant contacts access in Settings",
    ),
    ;

    /** True when the running OS predates the SDK that introduced this permission. Always false at
     *  minSdk 35 (every catalog permission exists on 35+), kept for the gate's granted-check symmetry. */
    val isImplicitlyGranted: Boolean get() = false

    companion object {
        private val byManifestString: Map<String, AppPermission> =
            entries.flatMap { unit -> unit.manifestPermissions.map { it to unit } }.toMap()

        /** Resolve the catalog unit for a raw android permission string (trampoline/rationale). */
        fun forManifestPermission(permission: String): AppPermission? = byManifestString[permission]
    }
}
