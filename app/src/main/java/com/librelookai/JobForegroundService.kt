package com.librelookai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicInteger

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

        private const val CHANNEL_ID   = "librelookai_jobs"
        private const val NOTIF_ID     = 1001

        fun acquire(context: Context) {
            context.startForegroundService(
                Intent(context, JobForegroundService::class.java).setAction(ACTION_ACQUIRE)
            )
        }

        fun release(context: Context) {
            context.startService(
                Intent(context, JobForegroundService::class.java).setAction(ACTION_RELEASE)
            )
        }
    }

    private val refCount = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACQUIRE -> {
                if (refCount.getAndIncrement() == 0) {
                    startForeground(NOTIF_ID, buildNotification())
                }
            }
            ACTION_RELEASE -> {
                if (refCount.decrementAndGet() <= 0) {
                    refCount.set(0)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
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
