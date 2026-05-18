package com.swaptr.aide.permission

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

// Translucent relay for non-Activity callers (IME/services); standard launchMode lets
// concurrent gate calls ride distinct instances keyed by EXTRA_REQUEST_ID.
@AndroidEntryPoint
class RuntimePermissionActivity : ComponentActivity() {

    @Inject lateinit var gate: RuntimePermissionGate

    private lateinit var permissions: Array<String>
    private lateinit var requestId: String

    private val launcher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val perResults = permissions.associateWith { perm ->
            val grantedFlag = results[perm] == true
            // false after "Don't allow" + "Don't ask again" — callers map to a
            // Settings deep-link UX instead of looping the dialog.
            val rationale = shouldShowRequestPermissionRationale(perm)
            PermissionOutcome(perm, grantedFlag, rationale)
        }
        gate.publish(requestId, MultiPermissionOutcome(perResults))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions = intent.getStringArrayExtra(EXTRA_PERMISSIONS) ?: emptyArray()
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        if (permissions.isEmpty() || requestId.isBlank()) {
            // Defensive: stale-instance relaunch would otherwise leave a translucent
            // window stuck on screen.
            finish()
            return
        }
        launcher.launch(permissions)
    }

    companion object {
        const val EXTRA_PERMISSIONS = "perms"
        const val EXTRA_REQUEST_ID = "req"
    }
}
