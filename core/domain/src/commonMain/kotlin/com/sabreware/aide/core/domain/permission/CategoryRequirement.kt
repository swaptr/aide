package com.sabreware.aide.core.domain.permission

/**
 * What enabling a tool category needs before its tools can run. Declared by the
 * [com.sabreware.aide.core.domain.tools.Toolset] that owns the category, so the requirement travels with the
 * tools instead of living in a table the Settings screen has to keep in step.
 */
sealed interface CategoryRequirement {

    /** No gate — enabling is immediate. */
    data object None : CategoryRequirement

    /** A standard runtime permission, requested via the system dialog (e.g. Calendar, Contacts). */
    data class Runtime(val permission: AppPermission) : CategoryRequirement

    /**
     * A special system permission granted from a Settings page rather than the runtime dialog — e.g.
     * Filesystem needs All files access ([SpecialPermission.ALL_FILES_ACCESS]). Still routed through
     * [RuntimePermissionGate] so it shares the rationale UI.
     */
    data class Special(val permission: SpecialPermission) : CategoryRequirement
}
