package com.sabreware.aide.platform.android.permission

import android.content.Intent

/**
 * Opens the activity that hosts the permission rationale and the system dialog.
 *
 * A port because the trampoline draws, and this module deliberately does not: naming
 * `RuntimePermissionActivity` here would put Compose on the compile classpath of everything that depends on
 * the platform layer for machinery. The implementation lives with the activity, in `:platform:android:ui`.
 *
 * The extras it carries are declared here ([Extras]) rather than on the activity, so both halves agree
 * without either importing the other.
 */
fun interface PermissionTrampoline {

    /** A bare Intent aimed at the trampoline; the gate adds the extras below. */
    fun intent(): Intent

    object Extras {
        const val PERMISSIONS = "perms"
        const val REQUEST_ID = "req"
        const val SHOW_RATIONALE = "show_rationale"
        const val SPECIAL = "special"
    }
}
