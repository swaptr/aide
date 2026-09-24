package com.sabreware.aide.app.tools.device

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.tools.BOTH_SURFACES
import com.sabreware.aide.core.domain.tools.CHAT_AND_VOICE
import com.sabreware.aide.core.domain.tools.ToolCategory
import com.sabreware.aide.core.domain.tools.Toolset
import com.sabreware.aide.core.domain.tools.ToolsetScope
import com.sabreware.aide.core.domain.tools.boolProp
import com.sabreware.aide.core.domain.tools.objectSchema
import com.sabreware.aide.app.tools.results.DeviceResult
import com.sabreware.aide.core.domain.tools.stringProp
import com.sabreware.aide.platform.android.intent.IntentDispatcher
import com.sabreware.aide.platform.android.intent.IntentDispatchers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "AideTools"
private val DEVICE_ERROR_CODES = setOf(
    "INVALID_ARGS", "NO_FLASH_UNIT", "TORCH_FAILED", "NO_HANDLER", "LAUNCH_FAILED",
)
private const val TORCH_VERIFY_TIMEOUT_MS = 1500L
private const val TORCH_VERIFY_POLL_MS = 50L

// setTorchMode needs no runtime permission; the map tool is a plain destination
// intent, so the whole category is CategoryRequirement.None.
class DeviceToolset(
    private val context: Context,
    private val dispatchers: IntentDispatchers,
) : Toolset {

    override val category = ToolCategory.Device
    override val displayName = "Device"
    override val blurb = "Toggle the flashlight and open locations on the map."

    // Stays eager: two small schemas, and "turn on the flashlight" is a latency-sensitive
    // one-shot — a RequestToolset round trip would double its response time.

    override fun tools(scope: ToolsetScope): List<AideTool> =
        asAideTools(dispatchers.forSurface(scope.surface))

    fun asAideTools(dispatcher: IntentDispatcher): List<AideTool> = listOf(
        setFlashlightTool(),
        showLocationOnMapTool(dispatcher),
    )

    private fun setFlashlightTool(): AideTool = AideTool.Function(
        name = "SetFlashlight",
        description = "Turn the device flashlight (torch) on or off. The change is " +
            "applied immediately — no app opens and no user confirmation is needed. " +
            "Use whenever the user asks for the flashlight, torch, or 'some light'.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "on" to boolProp("true to turn the flashlight on, false to turn it off."),
            ),
        ),
        handler = { args ->
            val on = args["on"]?.jsonPrimitive?.booleanOrNull
            Log.i(TAG, "SetFlashlight called: on=$on")
            if (on == null) return@Function deviceInvalidArgs("on (boolean) is required")
            setTorch(on)
        },
        surfaces = BOTH_SURFACES,
        errorCodes = DEVICE_ERROR_CODES,
        // Torch state can flip back out-of-band (quick settings, another camera
        // client) — serving a cached ok-envelope would skip the hardware call.
    )

    private fun showLocationOnMapTool(dispatcher: IntentDispatcher): AideTool = AideTool.Function(
        name = "ShowLocationOnMap",
        description = "Open the user's maps app showing a location. The location may " +
            "be a place name, a business, or an address. Use when the user asks where " +
            "something is, for directions to a place, or to see a location on the map.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "location" to stringProp(
                    "Location to search for, e.g. 'Eiffel Tower' or '1600 Amphitheatre Parkway'.",
                ),
            ),
        ),
        handler = { args ->
            val location = args["location"]?.jsonPrimitive?.content?.trim().orEmpty()
            // Where the user is going is location data; log that a lookup happened, not the place.
            Log.i(TAG, "ShowLocationOnMap called: ${location.length} chars")
            if (location.isEmpty()) return@Function deviceInvalidArgs("location is required")
            val intent = Intent(Intent.ACTION_VIEW, "geo:0,0?q=${Uri.encode(location)}".toUri())
            dispatch(intent, dispatcher)
        },
        surfaces = CHAT_AND_VOICE,
        errorCodes = DEVICE_ERROR_CODES,
    )

    // Live torch state per camera id, fed by the HAL's TorchCallback — there is no
    // synchronous getter, so this is the only way to verify a setTorchMode actually
    // landed. Registered once, lazily, on first flashlight use.
    private val torchState = ConcurrentHashMap<String, Boolean>()
    private val torchCallbackRegistered = AtomicBoolean(false)

    private fun ensureTorchCallback(cameraManager: CameraManager) {
        if (!torchCallbackRegistered.compareAndSet(false, true)) return
        cameraManager.registerTorchCallback(
            object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    torchState[cameraId] = enabled
                }

                override fun onTorchModeUnavailable(cameraId: String) {
                    torchState.remove(cameraId)
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    private fun setTorch(on: Boolean): JsonObject {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        ensureTorchCallback(cameraManager)
        val cameraId = try {
            // Prefer the back camera's flash (the usual torch); fall back to any flash unit.
            val withFlash = cameraManager.cameraIdList.filter { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            withFlash.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: withFlash.firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "SetFlashlight: camera enumeration failed", e)
            return DeviceResult.Err(
                "TORCH_FAILED",
                e.message ?: e.javaClass.simpleName,
            ).toEnvelope()
        }
        if (cameraId == null) {
            return DeviceResult.Err(
                "NO_FLASH_UNIT",
                "this device has no camera with a flash unit",
            ).toEnvelope()
        }
        return try {
            cameraManager.setTorchMode(cameraId, on)
            DeviceResult.Torch(on, verified = awaitTorchState(cameraId, on)).let {
                if (it.verified) it else it.copy(reason = "torch callback timeout")
            }.toEnvelope()
        } catch (e: Exception) {
            // CameraAccessException covers torch-in-use (camera open) and HAL errors.
            Log.w(TAG, "SetFlashlight: setTorchMode failed", e)
            DeviceResult.Err("TORCH_FAILED", e.message ?: e.javaClass.simpleName).toEnvelope()
        }
    }

    // Handlers are synchronous by contract (LiteRT calls them from a native thread),
    // so a short blocking poll mirrors the 1.5s verifier budget used elsewhere.
    private fun awaitTorchState(cameraId: String, expected: Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + TORCH_VERIFY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (torchState[cameraId] == expected) return true
            SystemClock.sleep(TORCH_VERIFY_POLL_MS)
        }
        return torchState[cameraId] == expected
    }

    private fun dispatch(intent: Intent, dispatcher: IntentDispatcher): JsonObject {
        if (intent.resolveActivity(context.packageManager) == null) {
            Log.w(TAG, "no handler for ${intent.action} ${intent.data}")
            return DeviceResult.Err(
                "NO_HANDLER",
                "no installed app handles ${intent.action}",
            ).toEnvelope()
        }
        return when (val outcome = dispatcher.launch(intent)) {
            is IntentDispatcher.LaunchOutcome.Launched ->
                DeviceResult.Launched(intent.action.orEmpty()).toEnvelope()
            is IntentDispatcher.LaunchOutcome.Failed -> {
                Log.w(TAG, "dispatcher launch failed for ${intent.action}", outcome.error)
                DeviceResult.Err(
                    "LAUNCH_FAILED",
                    outcome.error.message ?: outcome.error.javaClass.simpleName,
                ).toEnvelope()
            }
        }
    }

    private fun deviceInvalidArgs(reason: String): JsonObject =
        DeviceResult.Err("INVALID_ARGS", reason).toEnvelope()
}
