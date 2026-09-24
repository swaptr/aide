package com.sabreware.aide.platform.android

import android.content.Context
import android.content.Intent
import com.sabreware.aide.core.domain.navigation.DeepLinkDest

/**
 * Opens the main app at [destination] (a [DeepLinkDest] key) from a surface that is not itself an activity —
 * the IME service, the assistant session.
 *
 * Resolved through the package manager's launcher intent rather than by naming `MainActivity`: the surfaces
 * are modules of their own and the activity lives in `:app`, above them. The destination is the same string
 * the shell's deep-link router already understands, so this adds no new protocol.
 */
fun Context.launchAppAt(destination: String) {
    val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    intent.putExtra(DeepLinkDest.EXTRA_NAVIGATE_TO, destination)
    startActivity(intent)
}
