package com.sabreware.aide.app.platform

import android.app.ActivityManager
import android.content.Context
import com.sabreware.aide.core.domain.device.DeviceInfo

/**
 * Android [DeviceInfo] over `ActivityManager.MemoryInfo`, read fresh each time.
 *
 * `availMem` is the system-wide figure rather than this process's heap, which is the right one for admission:
 * native model weights are mapped outside the Java heap, so the heap limit says nothing about whether a
 * 4 GB GGUF will fit.
 */
class AndroidDeviceInfo(private val context: Context) : DeviceInfo {

    private fun memoryInfo(): ActivityManager.MemoryInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also(am::getMemoryInfo)
    }

    override val totalRamGb: Int get() = (memoryInfo().totalMem / GB).toInt()
    override val totalRamBytes: Long get() = memoryInfo().totalMem
    override val availableRamBytes: Long get() = memoryInfo().availMem

    private companion object {
        const val GB = 1024L * 1024L * 1024L
    }
}
