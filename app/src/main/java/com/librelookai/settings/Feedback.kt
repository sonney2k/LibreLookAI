package com.librelookai.settings

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.librelookai.BuildConfig
import com.librelookai.R
import com.librelookai.billing.ManagedBilling
import com.librelookai.gemini.ApiKeyStore
import com.librelookai.util.FeatureFlags
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Support inbox for in-app feedback. */
const val FEEDBACK_EMAIL = "feedback@librelook.ai"

/**
 * "Send feedback" dialog — opens an email to [FEEDBACK_EMAIL] with a standardized
 * subject and a description prompt. The user decides whether to attach a diagnostics
 * file (device/build info + recent app-only logcat, with API keys redacted). Re-provides
 * the locale-overridden context inside the [Dialog] window per CLAUDE.md's Dialog quirk.
 */
@Composable
fun FeedbackDialog(appState: String = "", onDismiss: () -> Unit) {
    val context = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    var attach by remember { mutableStateOf(true) }
    Dialog(onDismissRequest = onDismiss) {
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides parentConfiguration,
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = stringResource(R.string.settings_send_feedback),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = stringResource(R.string.feedback_dialog_body, FEEDBACK_EMAIL),
                        fontSize = 13.5.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Checkbox(checked = attach, onCheckedChange = { attach = it })
                        Column(modifier = Modifier.padding(start = 4.dp, top = 12.dp)) {
                            Text(
                                text = stringResource(R.string.feedback_attach_label),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Text(
                                text = stringResource(R.string.feedback_attach_sub),
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                        TextButton(onClick = { sendFeedbackEmail(context, attach, appState); onDismiss() }) {
                            Text(stringResource(R.string.settings_send_feedback))
                        }
                    }
                }
            }
        }
    }
}

/** Builds the email and hands off to the user's mail app, attaching diagnostics when asked. */
private fun sendFeedbackEmail(context: Context, attach: Boolean, appState: String = "") {
    val versionLine = "v${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_HASH})"
    val subject = context.getString(R.string.feedback_email_subject, versionLine)
    val body = buildString {
        appendLine(context.getString(R.string.feedback_email_prompt))
        appendLine()
        appendLine()
        appendLine("— — —")
        appendLine("LibreLookAI $versionLine")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
    }

    val attachmentUri: Uri? = if (attach) {
        runCatching {
            val dir = File(context.cacheDir, "feedback").apply { mkdirs() }
            val file = File(dir, "librelookai-diagnostics.txt")
            file.writeText(buildDiagnostics(context, appState))
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    } else {
        null
    }

    // A `mailto:` selector restricts the chooser to email apps but is launched as a
    // SENDTO handler, so mail apps drop the EXTRA_STREAM attachment. Instead, find the
    // email apps ourselves and target each with a real ACTION_SEND intent (which *does*
    // honour the attachment). Email apps = those handling SENDTO/mailto (visible via the
    // <queries> entry) AND ACTION_SEND of message/rfc822. That intersection is what cleanly
    // separates real mail clients (Gmail/Outlook/FairEmail) from apps that merely register
    // `mailto:` for deep links (PayPal, Glympse, …) — the latter would otherwise pollute the
    // picker and, since the system caps EXTRA_INITIAL_INTENTS at 2, crowd mail apps off it.
    val pm = context.packageManager
    val mailPkgs = runCatching {
        pm.queryIntentActivities(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")), 0)
            .map { it.activityInfo.packageName }.toSet()
    }.getOrDefault(emptySet())
    val targets = runCatching {
        pm.queryIntentActivities(Intent(Intent.ACTION_SEND).setType("message/rfc822"), 0)
            .filter { it.activityInfo.packageName in mailPkgs }
            .distinctBy { it.activityInfo.packageName }
    }.getOrDefault(emptyList())

    fun sendIntentFor(ri: ResolveInfo) = Intent(Intent.ACTION_SEND).apply {
        type = "message/rfc822"
        // Explicit component (not just setPackage) so it resolves even if the app exposes
        // several SEND activities, and is delivered as a real SEND → attachment is kept.
        setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
        putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
        if (attachmentUri != null) {
            putExtra(Intent.EXTRA_STREAM, attachmentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    if (targets.isNotEmpty()) {
        val intents = targets.map { sendIntentFor(it) }
        // LocalContext inside a Compose Dialog is a non-Activity context, so launching
        // requires NEW_TASK (otherwise startActivity throws AndroidRuntimeException).
        val launch = if (intents.size == 1) {
            intents[0].apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        } else {
            Intent.createChooser(intents[0], subject).apply {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.drop(1).toTypedArray())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // The chooser must also hold the grant so it forwards it to the picked app.
                if (attachmentUri != null) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        if (runCatching { context.startActivity(launch); true }.getOrDefault(false)) return
    }

    // Fallback: plain SENDTO mailto so at least a compose window opens (loses attachment).
    runCatching {
        val mailto = Uri.parse(
            "mailto:$FEEDBACK_EMAIL?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}",
        )
        context.startActivity(
            Intent(Intent.ACTION_SENDTO, mailto).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        val msg = context.getString(R.string.feedback_no_email_app, FEEDBACK_EMAIL)
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }
}

/**
 * Diagnostics report attached to feedback emails. Includes build, device, system, display,
 * memory, storage and coarse-network facts plus the app's own recent logcat — deliberately
 * excludes API keys, account identity, photos and Drive contents. Any `AIza…` / `ya29.…`
 * token that leaks into a log line is redacted before it leaves the device.
 */
fun buildDiagnostics(context: Context, appState: String = ""): String = buildString {
    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
    appendLine("LibreLookAI diagnostics")
    appendLine("=======================")
    appendLine("Generated: $ts")
    appendLine()

    appendLine("[App]")
    appendLine("  Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    appendLine("  Build: ${BuildConfig.BUILD_TYPE} · git ${BuildConfig.GIT_HASH}")
    appendLine("  Application id: ${BuildConfig.APPLICATION_ID}")
    appendLine("  Managed billing: ${ManagedBilling.enabled}")
    appendLine("  Power features: ${FeatureFlags.powerFeatures}")
    appendLine("  BYOK key set: ${ApiKeyStore.get(context).isNotBlank()}")
    runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        appendLine("  First install: ${fmt.format(Date(pi.firstInstallTime))}")
        appendLine("  Last update: ${fmt.format(Date(pi.lastUpdateTime))}")
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
        appendLine("  Installer: ${installer ?: "none (sideload)"}")
    }
    runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        appendLine("  Battery optimised: ${!pm.isIgnoringBatteryOptimizations(context.packageName)}")
    }
    appendLine()

    appendLine("[Device]")
    appendLine("  Manufacturer: ${Build.MANUFACTURER}")
    appendLine("  Brand: ${Build.BRAND}")
    appendLine("  Model: ${Build.MODEL}")
    appendLine("  Device: ${Build.DEVICE}")
    appendLine("  Product: ${Build.PRODUCT}")
    appendLine("  Hardware: ${Build.HARDWARE}")
    appendLine("  ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
    appendLine("  CPU cores: ${Runtime.getRuntime().availableProcessors()}")
    runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        appendLine("  Low-RAM device: ${am.isLowRamDevice}")
    }
    appendLine("  Fingerprint: ${Build.FINGERPRINT}")
    appendLine()

    appendLine("[System]")
    appendLine("  Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    appendLine("  Security patch: ${Build.VERSION.SECURITY_PATCH}")
    appendLine("  Build ID: ${Build.ID}")
    appendLine("  Locale: ${Locale.getDefault()}")
    appendLine("  Timezone: ${java.util.TimeZone.getDefault().id}")
    appendLine("  Uptime: ${android.os.SystemClock.elapsedRealtime() / 1000}s")
    appendLine()

    val cfg = context.resources.configuration
    val night = cfg.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    val dm = context.resources.displayMetrics
    appendLine("[Display]")
    appendLine("  ${dm.widthPixels}x${dm.heightPixels} @ ${dm.densityDpi}dpi (x${dm.density})")
    appendLine("  Font scale: ${cfg.fontScale} · Dark mode: $night")
    appendLine("  Smallest width: ${cfg.smallestScreenWidthDp}dp · Orientation: ${cfg.orientation}")
    appendLine()

    appendLine("[Memory]")
    val rt = Runtime.getRuntime()
    appendLine("  JVM heap: used ${mb(rt.totalMemory() - rt.freeMemory())} / max ${mb(rt.maxMemory())}")
    runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        appendLine("  System: avail ${mb(mi.availMem)} / total ${mb(mi.totalMem)} · low=${mi.lowMemory}")
    }
    appendLine()

    appendLine("[Storage]")
    runCatching {
        appendLine("  Internal: free ${mb(context.filesDir.usableSpace)} / total ${mb(context.filesDir.totalSpace)}")
    }
    appendLine()

    appendLine("[Network]")
    appendLine("  ${networkSummary(context)}")
    appendLine()

    if (appState.isNotBlank()) {
        appendLine(appState.trimEnd())
        appendLine()
    }

    appendLine("[Recent logs] (this app only, keys redacted)")
    appendLine("----------------------------------------------")
    appendLine(recentLogcat())
}

private fun mb(bytes: Long): String = "${bytes / (1024 * 1024)} MB"

private fun networkSummary(context: Context): String = runCatching {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "disconnected"
    val transport = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "other"
    }
    val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    "$transport · validated=$validated"
}.getOrDefault("unknown")

/**
 * Recent logcat for **this process** (`--pid`), then filtered down to lines actually relevant
 * to the app: our own tags + in-process libraries (Firestore/Coil/OkHttp/…) + genuine crashes
 * are kept; Android framework, GPU-driver, GMS-internal and OEM (Samsung) noise emitted *within*
 * our process is dropped (see [NOISE_TAG_PREFIXES]/[NOISE_TAG_EXACT]). Credentials redacted.
 */
private fun recentLogcat(): String = runCatching {
    val pid = android.os.Process.myPid().toString()
    // Grab a wide window since most lines are dropped by the noise filter below.
    val process = Runtime.getRuntime().exec(
        arrayOf("logcat", "-d", "-v", "time", "--pid=$pid", "-t", "2000"),
    )
    val raw = process.inputStream.bufferedReader().use { it.readText() }
    redactSecrets(dropNoiseLines(raw)).trim().ifBlank { "(no app-relevant logs)" }
}.getOrElse { "(logcat unavailable: ${it.message})" }

/** Matches the `-v time` header `MM-DD HH:MM:SS.mmm <prio>/<tag>(<pid>):`, capturing the tag. */
private val LOGCAT_HEADER = Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+[VDIWEF]/(.+?)\(\s*\d+\):""")

/** Tag prefixes (cover dynamic suffixes like `VRI[MainActivity]@abcdef`, `Adreno-UNKNOWN`). */
private val NOISE_TAG_PREFIXES = listOf(
    "VRI[", "Adreno", "HWUI", "HardwareRenderer", "BLAST", "BufferQueue", "SurfaceComposer",
    "Insets", "ViewRootImpl", "WindowManager", "WindowOnBack", "Choreographer", "InputTransport",
    "InputMethod", "ImeFocus", "ImeTracker", "ImeFocusController", "NativeCustomFrequencyManager",
    "CompatChangeReporter", "Kumiho", "IDS_TAG", "GoogleApiManager", "FlagRegistrar", "FlagStore",
    "ProviderInstaller", "ConnectivityManager", "NativeCrypto", "ProfileInstaller", "DecorView",
    "ApplicationLoaders", "nativeloader", "OpenGLRenderer", "Gralloc", "libEGL",
    "GraphicsEnvironment", "DynamiteModule", "qdgralloc", "SnapAlloc", "DesktopModeFlags",
)

/** Exact noise tags (short names that would over-match as prefixes). */
private val NOISE_TAG_EXACT = setOf(
    "FA", "Dialog", "Monitor", "BBA2", "CFMS", "Compiler", "com.librelookai",
    "MessageQueue", "Looper", "Parcel", "ViewPostIme", "ActivityThread", "vulkan", "Zygote",
    "DisplayManager", "ashmem", "System",
)

private fun isNoiseTag(tag: String): Boolean =
    tag in NOISE_TAG_EXACT || NOISE_TAG_PREFIXES.any { tag.startsWith(it) }

/**
 * Drops framework/vendor noise lines. Continuation lines (stack-trace `    at …`, multi-line
 * messages) carry no header, so they inherit the keep/drop decision of their parent line.
 */
private fun dropNoiseLines(raw: String): String = buildString {
    var keep = false
    for (line in raw.lineSequence()) {
        val tag = LOGCAT_HEADER.find(line)?.groupValues?.get(1)?.trim()
        if (tag != null) keep = !isNoiseTag(tag)
        if (keep) appendLine(line)
    }
}

private val SECRET_PATTERNS = listOf(
    Regex("""AIza[0-9A-Za-z\-_]{10,}"""),
    Regex("""ya29\.[0-9A-Za-z\-_.]+"""),
    Regex("""(?i)bearer\s+[0-9A-Za-z\-_.]+"""),
)

private fun redactSecrets(text: String): String {
    var out = text
    for (p in SECRET_PATTERNS) out = p.replace(out, "[REDACTED]")
    return out
}
