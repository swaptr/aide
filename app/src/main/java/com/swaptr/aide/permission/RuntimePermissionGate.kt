package com.swaptr.aide.permission

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first

private const val TAG = "RuntimePermissionGate"

// UUID-keyed MutableSharedFlow (not Channel): cancelled awaiter mustn't starve concurrent surfaces.
@Singleton
class RuntimePermissionGate @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {

    private data class KeyedOutcome(val requestId: String, val outcome: MultiPermissionOutcome)

    private val results: MutableSharedFlow<KeyedOutcome> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 16,
    )

    fun isGranted(permission: String): Boolean = ContextCompat.checkSelfPermission(
        appContext,
        permission,
    ) == PackageManager.PERMISSION_GRANTED

    fun areAllGranted(permissions: Collection<String>): Boolean =
        permissions.all { isGranted(it) }

    suspend fun request(permission: String): PermissionOutcome =
        requestAll(listOf(permission)).perPermission.getValue(permission)

    suspend fun requestAll(permissions: List<String>): MultiPermissionOutcome {
        if (permissions.isEmpty()) return MultiPermissionOutcome(emptyMap())
        // Android silently denies undeclared permissions with no dialog — log a
        // warning so a missed manifest entry surfaces as a diagnostic, not a no-op.
        permissions.forEach { perm ->
            if (perm !in declaredRuntimePermissions()) {
                Log.w(
                    TAG,
                    "Permission '$perm' is not declared in AndroidManifest.xml; " +
                        "Android will silently deny without showing the system dialog.",
                )
            }
        }
        if (areAllGranted(permissions)) {
            return MultiPermissionOutcome.synthesizeGranted(permissions)
        }
        val id = UUID.randomUUID().toString()
        val intent = Intent(appContext, RuntimePermissionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(RuntimePermissionActivity.EXTRA_PERMISSIONS, permissions.toTypedArray())
            putExtra(RuntimePermissionActivity.EXTRA_REQUEST_ID, id)
        }
        appContext.startActivity(intent)
        return results.first { it.requestId == id }.outcome
    }

    internal fun publish(requestId: String, outcome: MultiPermissionOutcome) {
        results.tryEmit(KeyedOutcome(requestId, outcome))
    }

    private val declaredPermsLazy: Set<String> by lazy {
        @Suppress("DEPRECATION")
        val info = appContext.packageManager.getPackageInfo(
            appContext.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        info.requestedPermissions?.toSet().orEmpty()
    }

    private fun declaredRuntimePermissions(): Set<String> = declaredPermsLazy
}
