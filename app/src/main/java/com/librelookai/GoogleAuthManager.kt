package com.librelookai

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private const val TAG = "GoogleAuthManager"

class GoogleAuthManager(private val context: Context) {

    companion object {
        const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
    }

    private val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(Scope(DRIVE_SCOPE))

    private val gso = gsoBuilder.apply {
        // Request ID token only when Firebase web client ID is configured
        val webClientId = BuildConfig.FIREBASE_WEB_CLIENT_ID
        if (webClientId.isNotBlank()) requestIdToken(webClientId)
    }.build()

    private val client = GoogleSignIn.getClient(context, gso)

    fun getSignInIntent(): Intent = client.signInIntent

    fun currentAccount(): GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(context)

    fun isSignedIn(): Boolean {
        val account = currentAccount() ?: return false
        return GoogleSignIn.hasPermissions(account, Scope(DRIVE_SCOPE))
    }

    /** Returns a valid OAuth2 access token for the Drive.file scope. Blocks the calling thread. */
    suspend fun getAccessToken(): String = withContext(Dispatchers.IO) {
        val account = currentAccount() ?: error("Not signed in")
        GoogleAuthUtil.getToken(context, account.account!!, "oauth2:$DRIVE_SCOPE")
    }

    /**
     * Signs in to Firebase using the ID token from the most-recently signed-in Google account.
     * No-op (returns false) if Firebase is not configured or the Google account has no ID token.
     */
    suspend fun signInToFirebase(): Boolean {
        return try {
            if (FirebaseApp.getApps(context).isEmpty()) return false
            val account = currentAccount() ?: return false
            val idToken = account.idToken ?: return false
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            FirebaseAuth.getInstance().signInWithCredential(credential).await()
            Log.d(TAG, "Firebase sign-in successful")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Firebase sign-in failed (non-fatal): ${e.message}")
            false
        }
    }

    suspend fun signOut() = suspendCancellableCoroutine<Unit> { cont ->
        client.signOut().addOnCompleteListener { cont.resume(Unit) }
    }
}
