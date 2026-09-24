package com.sabreware.aide.app.permission

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.sabreware.aide.platform.android.permission.AndroidRuntimePermissionGate
import com.sabreware.aide.platform.android.permission.PermissionRationale
import com.sabreware.aide.platform.android.permission.PermissionTrampoline
import com.sabreware.aide.platform.android.permission.androidIsGranted
import com.sabreware.aide.platform.android.permission.settingsIntent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import com.sabreware.aide.core.designsystem.theme.AideTheme
import com.sabreware.aide.core.domain.permission.MultiPermissionOutcome
import com.sabreware.aide.core.domain.permission.PermissionOutcome
import com.sabreware.aide.core.domain.permission.SpecialPermission
import com.sabreware.aide.core.domain.llm.Surface
import com.sabreware.aide.core.domain.presence.SurfacePresence
import org.koin.android.ext.android.inject

// Transparent relay for non-Activity callers (IME/assistant/services): the official way to surface
// the *system* permission dialog from a context that can't request directly, and the single place
// where the in-app rationale ("why we need this") and the permanently-denied "Open Settings" dialog
// are rendered so every surface inherits them. Standard launchMode lets concurrent gate calls ride
// distinct instances keyed by PermissionTrampoline.Extras.REQUEST_ID.
//
// Handles two kinds of request: runtime permissions (PermissionTrampoline.Extras.PERMISSIONS, via the system dialog) and a
// special permission (PermissionTrampoline.Extras.SPECIAL, e.g. All files access) granted from a Settings page. Both show
// the same rationale dialog first; the special path then opens the Settings page instead of the
// system dialog and re-checks the grant on return.
class RuntimePermissionActivity : ComponentActivity() {

    private val gate: AndroidRuntimePermissionGate by inject()
    private val presence: SurfacePresence by inject()

    private lateinit var permissions: Array<String>
    private var requestId: String = ""
    private var special: SpecialPermission? = null

    private enum class Screen { RATIONALE, SETTINGS, NONE }

    // NONE while the system dialog / Settings page is up (nothing of ours drawn over the window).
    private val screen = mutableStateOf(Screen.NONE)

    // Runtime rationale copy; null for the special path (which uses the special's own copy).
    private var rationale: PermissionRationale.Rationale? = null

    // The denied outcome to publish once the Settings dialog is dismissed/actioned.
    private var deniedOutcomeForSettings: MultiPermissionOutcome? = null

    private val launcher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val outcome = MultiPermissionOutcome(
            permissions.associateWith { perm ->
                PermissionOutcome(
                    permission = perm,
                    granted = results[perm] == true,
                    // false after a permanent deny — used to route to the Settings dialog.
                    shouldShowRationale = shouldShowRequestPermissionRationale(perm),
                )
            },
        )
        // Permanently denied → launch() returned without a system dialog; offer Settings instead.
        if (!outcome.allGranted && outcome.anyPermanentlyDenied) {
            deniedOutcomeForSettings = outcome
            screen.value = Screen.SETTINGS
        } else {
            finishWith(outcome)
        }
    }

    // Special-permission grant happens on a Settings page that returns no useful result code, so we
    // re-check the grant state on return and publish the resulting outcome.
    private val specialLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { _ -> finishWith(specialOutcome()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions = intent.getStringArrayExtra(PermissionTrampoline.Extras.PERMISSIONS) ?: emptyArray()
        requestId = intent.getStringExtra(PermissionTrampoline.Extras.REQUEST_ID).orEmpty()
        special = intent.getStringExtra(PermissionTrampoline.Extras.SPECIAL)
            ?.let { name -> runCatching { SpecialPermission.valueOf(name) }.getOrNull() }
        if (requestId.isBlank() || (special == null && permissions.isEmpty())) {
            // Defensive: stale-instance relaunch would otherwise leave a transparent window stuck.
            finish()
            return
        }

        if (special != null) {
            // Always surface the rationale before sending the user to the special-access Settings
            // page — the whole point of routing this through the trampoline.
            screen.value = Screen.RATIONALE
        } else {
            // Canonical Android flow: the first ask goes straight to the system dialog. Surface our
            // in-app "why we need this" rationale only when the OS signals one is warranted — i.e.
            // shouldShowRequestPermissionRationale is true for any requested permission, which it
            // becomes after a prior denial. Tapping "Continue" then re-launches the system dialog; a
            // permanent deny returns from launch() with no system dialog → routed to the Settings
            // dialog in the callback. PermissionTrampoline.Extras.SHOW_RATIONALE lets a caller suppress our dialog entirely
            // (e.g. its own UI already explained).
            val rationaleAllowed = intent.getBooleanExtra(PermissionTrampoline.Extras.SHOW_RATIONALE, true)
            val osWantsRationale = permissions.any { shouldShowRequestPermissionRationale(it) }
            rationale = PermissionRationale.forPermissions(permissions.asList())
            if (rationaleAllowed && osWantsRationale) {
                screen.value = Screen.RATIONALE
            } else {
                launcher.launch(permissions)
            }
        }

        setContent {
            AideTheme {
                // AlertDialog draws its own full-screen scrim and centring; the host activity is an
                // invisible floating window (see Theme.Aide.PermissionTrampoline). NONE renders
                // nothing so the system permission dialog / Settings page shows unobstructed.
                when (screen.value) {
                    Screen.RATIONALE -> AlertDialog(
                        onDismissRequest = { denyAndFinish() }, // cancel / back / tap-outside = deny
                        title = { Text(rationaleTitle()) },
                        text = { Text(rationaleMessage()) },
                        confirmButton = {
                            TextButton(onClick = {
                                screen.value = Screen.NONE
                                val sp = special
                                if (sp != null) {
                                    // The turn that asked is waiting on this grant; Settings covering the app is
                                    // not the user leaving it.
                                    presence.awayForResult(Surface.CHAT)
                                    specialLauncher.launch(sp.settingsIntent(this@RuntimePermissionActivity))
                                } else {
                                    launcher.launch(permissions)
                                }
                            }) { Text("Continue") }
                        },
                        dismissButton = {
                            TextButton(onClick = { denyAndFinish() }) { Text("Not now") }
                        },
                    )

                    Screen.SETTINGS -> AlertDialog(
                        onDismissRequest = { finishWith(deniedOutcomeForSettings ?: deniedOutcome()) },
                        title = { Text("Permission blocked") },
                        text = {
                            Text(
                                "Aide can't ask for this again. Turn it on in Aide's app permissions " +
                                    "in system settings.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                openAppSettings()
                                finishWith(deniedOutcomeForSettings ?: deniedOutcome())
                            }) { Text("Open Settings") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                finishWith(deniedOutcomeForSettings ?: deniedOutcome())
                            }) { Text("Cancel") }
                        },
                    )

                    Screen.NONE -> Unit
                }
            }
        }
    }

    private fun rationaleTitle(): String = special?.rationaleTitle ?: rationale?.title.orEmpty()

    private fun rationaleMessage(): String = special?.rationaleMessage ?: rationale?.message.orEmpty()

    // "Not now" / back: deny. For a special permission this publishes its current (un)granted state.
    // True once an outcome has gone to the gate, so [onDestroy] does not publish a second, wrong one.
    private var published = false

    private fun denyAndFinish() =
        finishWith(if (special != null) specialOutcome() else deniedOutcome())

    private fun deniedOutcome(): MultiPermissionOutcome = MultiPermissionOutcome(
        permissions.associateWith { perm ->
            PermissionOutcome(
                permission = perm,
                granted = false,
                shouldShowRationale = shouldShowRequestPermissionRationale(perm),
            )
        },
    )

    // Single-entry outcome keyed by the special's name; granted reflects the live grant state.
    private fun specialOutcome(): MultiPermissionOutcome {
        val sp = special ?: return MultiPermissionOutcome(emptyMap())
        return MultiPermissionOutcome(
            mapOf(
                sp.name to PermissionOutcome(
                    permission = sp.name,
                    granted = sp.androidIsGranted(),
                    shouldShowRationale = false,
                ),
            ),
        )
    }

    private fun finishWith(outcome: MultiPermissionOutcome) {
        published = true
        gate.publish(requestId, outcome)
        finish()
    }

    /**
     * The last resort. Every normal path goes through [finishWith], but the system can destroy this
     * activity without any of them running — low memory while the assistant overlay is up is the realistic
     * case. The awaiter on the other side is suspended, and callers hold locks across it, so "no answer" is
     * strictly worse than "not granted": publish the abandoned outcome so it resumes and can ask again.
     */
    override fun onDestroy() {
        super.onDestroy()
        if (published || requestId.isBlank()) return
        published = true
        val units = if (special != null) listOf(special!!.name) else permissions.asList()
        gate.publish(requestId, MultiPermissionOutcome.synthesizeAbandoned(units))
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

}
