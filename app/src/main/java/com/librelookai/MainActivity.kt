package com.librelookai

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.librelookai.data.drive.DriveRepository
import com.librelookai.ml.EmbeddingService
import com.librelookai.util.Analytics
import com.librelookai.util.StartupGate
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var driveRepository: DriveRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install before super/setContent so the system splash (launcher icon on white) is owned by
        // us. Hold it past a brand-moment minimum AND until the first composition reports it's done
        // restoring (StartupGate.contentReady) — so a general cold start hands straight to the app
        // instead of flashing the restore-progress card behind the fading splash. Capped at a max so
        // a genuinely long reinstall-restore still releases the splash and shows the progress card.
        val splash = installSplashScreen()
        val splashStart = SystemClock.uptimeMillis()
        splash.setKeepOnScreenCondition {
            val elapsed = SystemClock.uptimeMillis() - splashStart
            elapsed < SPLASH_MIN_DURATION_MS ||
                (!StartupGate.contentReady && elapsed < SPLASH_MAX_DURATION_MS)
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Analytics.init(applicationContext)
        EmbeddingService.init(this)
        com.librelookai.gemini.PricingClient.start(applicationContext)
        com.librelookai.gemini.ModelPricingClient.start(applicationContext)
        setContent { AppContent(this, driveRepository) }
    }

    private companion object {
        /** Brand-moment floor: keep the splash at least this long even if content is instantly ready. */
        const val SPLASH_MIN_DURATION_MS = 800L
        /** Safety cap: release even if the restore hasn't settled, so a long restore reveals its card. */
        const val SPLASH_MAX_DURATION_MS = 2500L
    }
}
