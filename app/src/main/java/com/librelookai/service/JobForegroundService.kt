package com.librelookai.service
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicInteger
import com.librelookai.R

/**
 * Foreground service that prevents the OS from killing the app process during long-running
 * wardrobe jobs (photo processing, import, retag, repair-and-sync, background removal).
 *
 * Start with [ACTION_ACQUIRE] before a job; send [ACTION_RELEASE] when done. The service
 * promotes itself to the foreground on the first acquire and calls stopSelf() when the
 * last release brings the reference count to zero.
 */
class JobForegroundService : Service() {

    companion object {
        const val ACTION_ACQUIRE = "com.librelookai.job.ACQUIRE"
        const val ACTION_RELEASE = "com.librelookai.job.RELEASE"

        private const val TAG          = "JobForegroundService"
        private const val CHANNEL_ID   = "librelookai_jobs"
        private const val NOTIF_ID     = 1001

        private val refCount = AtomicInteger(0)

        fun acquire(context: Context) {
            Log.d(TAG, "acquire() called")
            context.startForegroundService(
                Intent(context, JobForegroundService::class.java).setAction(ACTION_ACQUIRE)
            )
        }

        fun release(context: Context) {
            Log.d(TAG, "release() called")
            context.startService(
                Intent(context, JobForegroundService::class.java).setAction(ACTION_RELEASE)
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate()")
        ensureChannel()
        // Aggressively start foreground in onCreate to satisfy Android 12+ requirements
        // as early as possible, regardless of which intent triggered the start.
        safeStartForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand(action=$action, refCount=${refCount.get()})")

        when (action) {
            ACTION_ACQUIRE -> {
                refCount.incrementAndGet()
                safeStartForeground()
            }
            ACTION_RELEASE -> {
                if (refCount.decrementAndGet() <= 0) {
                    refCount.set(0)
                    Log.d(TAG, "Stopping service (count reached 0)")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    safeStartForeground()
                }
            }
            null -> {
                // Restarted by system
                Log.d(TAG, "Restarted with null intent")
                if (refCount.get() > 0) {
                    safeStartForeground()
                } else {
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    private fun safeStartForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.job_notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) }
            )
        }
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.job_notif_title))
            .setContentText(getString(R.string.job_notif_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
}
