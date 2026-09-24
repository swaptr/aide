package com.sabreware.aide.platform.android.permission

import com.sabreware.aide.core.domain.permission.AppPermission
/**
 * In-app "why we need this" copy shown by the permission trampoline before the system dialog.
 * Thin adapter over [AppPermission] (the single source of truth) — keeps the string-keyed signature
 * the trampoline calls with, but holds no copy of its own.
 */
object PermissionRationale {
    data class Rationale(val title: String, val message: String)

    private const val GENERIC_FALLBACK = "Aide needs this permission to use the feature you tapped."

    /**
     * One [Rationale] for a (possibly multi-permission) request. Permissions in the same catalog
     * unit (e.g. calendar read+write) resolve to identical rationales and collapse via `distinct()`.
     */
    fun forPermissions(permissions: List<String>): Rationale {
        val distinct = permissions.map { forPermission(it) }.distinct()
        return when {
            distinct.isEmpty() -> Rationale("Permission needed", GENERIC_FALLBACK)
            distinct.size == 1 -> distinct.first()
            else -> Rationale("Permissions needed", distinct.joinToString("\n\n") { it.message })
        }
    }

    private fun forPermission(permission: String): Rationale =
        AppPermission.forManifestPermission(permission)
            ?.let { Rationale(it.rationaleTitle, it.rationaleMessage) }
            ?: Rationale(
                "Permission needed",
                "Aide needs the ${prettyName(permission)} permission to use this feature.",
            )

    // android.permission.READ_CALENDAR -> "read calendar"
    private fun prettyName(permission: String): String =
        permission.substringAfterLast('.').replace('_', ' ').lowercase()
}
