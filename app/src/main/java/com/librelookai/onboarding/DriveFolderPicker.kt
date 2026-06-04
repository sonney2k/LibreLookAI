package com.librelookai.onboarding

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.librelookai.BuildConfig
import com.librelookai.auth.GoogleAuthManager
import com.librelookai.data.drive.DriveRepository

/**
 * Full-screen Google Picker (folder selection) hosted in a WebView — the only way to grant a
 * `drive.file`-scoped app access to a pre-existing folder. Selecting the `LibreLookAI` folder
 * grants recursive access to it and all its contents (closets, sidecars, outfits, trips), which
 * we persist as the Drive root via [DriveRepository.setPickedRootFolder].
 *
 * [onResult] is called with the picked folder ID, or `null` if the user cancelled / auth failed.
 *
 * Picker quirks handled here: it needs an OAuth access token ([GoogleAuthManager.getAccessToken]),
 * an API key + Cloud project number (`setDeveloperKey` / `setAppId`), and a real https origin —
 * WebViews loading `file://`/`data:` report a null origin the Picker rejects, so we load via
 * [WebView.loadDataWithBaseURL] with [PICKER_ORIGIN] and pass the same value to `setOrigin`.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DriveFolderPicker(onResult: (folderId: String?) -> Unit) {
    val context = LocalContext.current
    val latestOnResult by rememberUpdatedState(onResult)
    var token by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        token = runCatching { GoogleAuthManager(context).getAccessToken() }.getOrNull()
        if (token == null) failed = true
    }

    Dialog(
        onDismissRequest = { latestOnResult(null) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
            val tok = token
            when {
                failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Couldn't connect to Google Drive. Try again.",
                        Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { latestOnResult(null) }) { Text("Close") }
                }
                tok == null -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Text("Opening Google Drive…", Modifier.padding(top = 16.dp))
                }
                else -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            // The Picker embeds docs.google.com in our origin's iframe; without
                            // third-party cookies it shows "Can't access your Google Account".
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?,
                                ) {
                                    Log.w(TAG, "resource error ${error?.errorCode} ${error?.description} @ ${request?.url}")
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                                    Log.d(TAG, "JS[${m.messageLevel()}] ${m.message()} @${m.lineNumber()}")
                                    return true
                                }
                            }
                            addJavascriptInterface(
                                object {
                                    @JavascriptInterface fun getToken() = tok
                                    @JavascriptInterface fun getKey() = BuildConfig.PICKER_API_KEY
                                    @JavascriptInterface fun getAppId() = BuildConfig.PICKER_APP_ID
                                    @JavascriptInterface fun getOrigin() = PICKER_ORIGIN
                                    @JavascriptInterface fun log(msg: String) { Log.d(TAG, "page: $msg") }

                                    @JavascriptInterface
                                    fun onPicked(id: String, name: String) {
                                        // Returns the picked folder as a MIGRATION SOURCE — the caller
                                        // copies it into a fresh app-owned root. We deliberately do NOT
                                        // set it as the active root (the app must never write into the
                                        // legacy folder; see DriveMigration).
                                        Log.d(TAG, "onPicked id=$id name=$name")
                                        post { latestOnResult(id) }
                                    }

                                    @JavascriptInterface
                                    fun onCancelled() = post { latestOnResult(null) }
                                },
                                "AndroidPicker",
                            )
                            loadDataWithBaseURL(PICKER_ORIGIN, PICKER_HTML, "text/html", "utf-8", null)
                        }
                    },
                )
            }
        }
    }
}

private const val TAG = "DrivePicker"

/** Authorized https origin for the Picker iframe (must match the WebView base URL). */
private const val PICKER_ORIGIN = "https://librelookai-firebase.firebaseapp.com"

private val PICKER_HTML = """
<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>html,body{margin:0;height:100%;background:#fff;font-family:sans-serif}#s{padding:24px;color:#555}</style>
</head><body>
<div id="s">Loading Google Drive…</div>
<script>
function status(t){ var e=document.getElementById('s'); if(e) e.innerText=t; AndroidPicker.log('status: '+t); }
window.onerror = function(m,u,l){ status('JS error: '+m+' @'+l); return false; };
function onApiLoad(){ AndroidPicker.log('api.js loaded, loading picker module'); gapi.load('picker', onPickerLoad); }
function onPickerLoad(){
  try {
    AndroidPicker.log('picker module loaded; building (appId='+AndroidPicker.getAppId()+', tokenLen='+(AndroidPicker.getToken()||'').length+')');
    var view = new google.picker.DocsView(google.picker.ViewId.FOLDERS)
        .setSelectFolderEnabled(true)
        .setIncludeFolders(true)
        .setMimeTypes('application/vnd.google-apps.folder');
    var picker = new google.picker.PickerBuilder()
        .setOAuthToken(AndroidPicker.getToken())
        .setDeveloperKey(AndroidPicker.getKey())
        .setAppId(AndroidPicker.getAppId())
        .setOrigin(AndroidPicker.getOrigin())
        .addView(view)
        .setTitle('Select your LibreLookAI folder')
        .setCallback(pickerCallback)
        .build();
    picker.setVisible(true);
    status('');
    AndroidPicker.log('picker visible');
  } catch(e){ status('Picker build error: '+e); }
}
function pickerCallback(data){
  var action = data[google.picker.Response.ACTION];
  AndroidPicker.log('callback action='+action);
  if(action === google.picker.Action.PICKED){
    var doc = data[google.picker.Response.DOCUMENTS][0];
    AndroidPicker.onPicked(doc[google.picker.Document.ID], doc[google.picker.Document.NAME] || '');
  } else if(action === google.picker.Action.CANCEL){
    AndroidPicker.onCancelled();
  }
}
</script>
<script src="https://apis.google.com/js/api.js" onload="onApiLoad()" onerror="status('failed to load api.js')"></script>
</body></html>
""".trimIndent()
