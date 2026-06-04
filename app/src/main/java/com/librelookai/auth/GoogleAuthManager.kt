package com.librelookai.auth
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await
import com.librelookai.data.drive.await
import com.librelookai.BuildConfig

private const val TAG = "GoogleAuthManager"
private const val PREFS = "auth"
private const val KEY_SIGNED_IN = "signed_in"

class GoogleAuthManager(private val context: Context) {

    companion object {
        // Migration build uses full `drive` (reads legacy to copy); production ships drive.file.
        // Selected by the BuildConfig.DRIVE_FULL_SCOPE flag (← local.properties `drive.full.scope`).
        val DRIVE_SCOPE: String =
            if (BuildConfig.DRIVE_FULL_SCOPE) "https://www.googleapis.com/auth/drive"
            else "https://www.googleapis.com/auth/drive.file"
    }

    private val credentialManager = CredentialManager.create(context)
    private val authorizationClient = Identity.getAuthorizationClient(context)

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSignedIn(): Boolean = prefs().getBoolean(KEY_SIGNED_IN, false)

    private fun setSignedIn(value: Boolean) {
        prefs().edit().putBoolean(KEY_SIGNED_IN, value).apply()
    }

    private fun buildAuthRequest(): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
        .build()

    /**
     * Starts the sign-in/authorization flow for the Drive scope.
     * Returns null if already authorized (signed-in state is set automatically).
     * Returns a [PendingIntent] that must be launched via [StartIntentSenderForResult] when user
     * consent is required (first sign-in or scope not yet granted).
     */
    suspend fun beginSignIn(): PendingIntent? {
        val result: AuthorizationResult = authorizationClient.authorize(buildAuthRequest()).await()
        return if (result.hasResolution()) {
            result.pendingIntent
        } else {
            setSignedIn(true)
            null
        }
    }

    /**
     * Processes the result Intent from the authorization PendingIntent.
     * Returns true if authorization was granted and Drive scope access is available.
     */
    fun handleAuthorizationResult(data: Intent?): Boolean {
        return try {
            val result = authorizationClient.getAuthorizationResultFromIntent(data ?: Intent())
            val success = result.accessToken != null
            if (success) setSignedIn(true)
            success
        } catch (e: ApiException) {
            Log.w(TAG, "Authorization failed: status=${e.statusCode}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Authorization result error: ${e.message}")
            false
        }
    }

    /**
     * Returns a valid OAuth2 access token for the Drive scope.
     *
     * If the authorization is gone (revoked / expired / OAuth client deleted),
     * `accessToken` comes back null. Rather than crash with a raw exception on
     * whatever background coroutine happened to ask for a token, we clear the
     * signed-in flag, signal the UI to re-authorize via [AuthEvents], and abort
     * this coroutine cleanly by throwing [ReauthRequiredException] (a
     * CancellationException — never fatal). See [AuthEvents] for the rationale.
     */
    suspend fun getAccessToken(): String {
        val result = authorizationClient.authorize(buildAuthRequest()).await()
        result.accessToken?.let { return it }
        setSignedIn(false)
        AuthEvents.emitSessionExpired()
        throw ReauthRequiredException()
    }

    /**
     * Best-effort: sign in to Firebase using a silently fetched Google ID token.
     * No-op if Firebase is not configured, already signed in, or silent fetch fails.
     */
    suspend fun signInToFirebase(activity: ComponentActivity) {
        if (BuildConfig.FIREBASE_WEB_CLIENT_ID.isBlank()) return
        if (FirebaseApp.getApps(context).isEmpty()) return
        if (FirebaseAuth.getInstance().currentUser != null) return
        try {
            val option = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(true)
                .setServerClientId(BuildConfig.FIREBASE_WEB_CLIENT_ID)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()
            val response = credentialManager.getCredential(activity, request)
            val idToken = GoogleIdTokenCredential.createFrom(response.credential.data).idToken
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            FirebaseAuth.getInstance().signInWithCredential(credential).await()
            Log.d(TAG, "Firebase sign-in successful")
        } catch (e: GetCredentialCancellationException) {
            // User cancelled — not an error
        } catch (e: Exception) {
            Log.w(TAG, "Firebase sign-in failed (non-fatal): ${e.message}")
        }
    }

    suspend fun signOut() {
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            Log.w(TAG, "Clear credential state failed: ${e.message}")
        }
        setSignedIn(false)
    }
}
