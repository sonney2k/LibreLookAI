package com.librelookai.util
import android.os.SystemClock
import android.text.format.Formatter
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.librelookai.R
import kotlin.math.exp
import kotlinx.coroutines.delay

private val Gold = Color(0xFFFFD700)

@Composable
fun AiProcessingOverlay(label: String = stringResource(R.string.ai_working), modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "ai")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "rotate",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "pulse",
    )

    // Live network progress for the in-flight Gemini call (null until it starts / after it
    // ends). The bus arrives via LocalGeminiProgress (injected @Singleton, § 3 slice 5); an
    // unprovided Local (previews/tests) degrades to the time-based estimate only.
    val progress = com.librelookai.LocalGeminiProgress.current?.state?.collectAsState()?.value
    // Ticking monotonic clock so the elapsed counter and the time-based wait bar advance.
    val composeStart = remember { SystemClock.elapsedRealtime() }
    var nowMillis by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            nowMillis = SystemClock.elapsedRealtime()
            delay(250)
        }
    }

    val startAt = progress?.startedAtMillis ?: composeStart
    val elapsedSec = ((nowMillis - startAt) / 1000L).toInt().coerceAtLeast(0)
    val uploading = progress?.uploading == true && (progress?.bytesTotal ?: 0) > 0

    Box(
        modifier = modifier.background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(72.dp),
                    strokeWidth = 2.dp,
                    color = Gold,
                )
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                        .graphicsLayer { rotationZ = rotation; alpha = pulse },
                    tint = Gold,
                )
            }
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )

            // Phase: real upload-byte bar while sending, then a time-based estimate of the AI wait.
            if (uploading) {
                val sent = progress?.bytesSent ?: 0L
                val total = progress?.bytesTotal ?: 0L
                LinearProgressIndicator(
                    progress = { (sent.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.width(220.dp),
                    color = Gold,
                    trackColor = Color.White.copy(alpha = 0.25f),
                )
                val ctx = LocalContext.current
                ProgressCaption(
                    stringResource(
                        R.string.ai_progress_uploading,
                        Formatter.formatShortFileSize(ctx, sent),
                        Formatter.formatShortFileSize(ctx, total),
                    ),
                )
            } else {
                // Asymptotic fill toward ~95% over the expected window so it never "completes"
                // before the real answer lands; resets its baseline once the upload finishes.
                val waitBaseline = progress?.uploadDoneAtMillis?.takeIf { it > 0L } ?: startAt
                val waitSec = ((nowMillis - waitBaseline) / 1000f).coerceAtLeast(0f)
                val fraction = 0.95f * (1f - exp(-waitSec / 20f))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.width(220.dp),
                    color = Gold,
                    trackColor = Color.White.copy(alpha = 0.25f),
                )
                ProgressCaption(stringResource(R.string.ai_progress_waiting))
            }

            ProgressCaption(stringResource(R.string.ai_progress_timing, elapsedSec))
        }
    }
}

@Composable
private fun ProgressCaption(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.7f),
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}
