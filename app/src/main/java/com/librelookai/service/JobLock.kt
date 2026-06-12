package com.librelookai.service

import android.content.Context
import android.os.PowerManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Refcounted process-keepalive for background jobs (the ingestion queue and the wardrobe's
 * bulk maintenance ops): the first [acquire] starts the [JobForegroundService] and grabs a
 * partial wake lock, the last [release] drops both. Extracted from WardrobeViewModel
 * (refactor § 5 slice 5) so the `ItemIngestionPipeline` singleton and the VM's remaining
 * bulk workflows share one refcount.
 */
@Singleton
class JobLock @Inject constructor(@ApplicationContext private val context: Context) {

    companion object { private const val TAG = "JobLock" }

    private var wakeLock: PowerManager.WakeLock? = null
    private val activeJobCount = AtomicInteger(0)

    private val _needsBatteryExemption = MutableStateFlow(false)
    /**
     * Flips true when a job starts while the app is not exempt from battery optimization —
     * on many OEM ROMs the OS kills even foreground services unless the app is explicitly
     * exempted. Mirrored into [com.librelookai.wardrobe.WardrobeUiState.needsBatteryExemption].
     */
    val needsBatteryExemption: StateFlow<Boolean> = _needsBatteryExemption.asStateFlow()

    fun dismissBatteryWarning() { _needsBatteryExemption.value = false }

    fun acquire() {
        if (activeJobCount.getAndIncrement() == 0) {
            // Foreground service keeps the process alive while any job is running
            JobForegroundService.acquire(context)
            // Wake lock keeps the CPU awake if the screen turns off mid-job
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LibreLookAI:Jobs")
                    .also { it.setReferenceCounted(false) }
            }
            wakeLock!!.acquire(30 * 60 * 1000L) // 30-minute safety timeout
            Log.d(TAG, "Wake lock acquired")
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                _needsBatteryExemption.value = true
            }
        }
    }

    fun release() {
        if (activeJobCount.decrementAndGet() == 0) {
            wakeLock?.let { if (it.isHeld) it.release() }
            JobForegroundService.release(context)
            Log.d(TAG, "Wake lock released")
        }
    }
}
