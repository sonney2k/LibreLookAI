package com.librelookai

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class GoogleAuthManager(private val context: Context) {

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    }

    private val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(Scope(DRIVE_FILE_SCOPE))
        .build()

    private val client = GoogleSignIn.getClient(context, gso)

    fun getSignInIntent(): Intent = client.signInIntent

    fun currentAccount(): GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(context)

    fun isSignedIn(): Boolean {
        val account = currentAccount() ?: return false
        return GoogleSignIn.hasPermissions(account, Scope(DRIVE_FILE_SCOPE))
    }

    /** Returns a valid OAuth2 access token for the Drive.file scope. Blocks the calling thread. */
    suspend fun getAccessToken(): String = withContext(Dispatchers.IO) {
        val account = currentAccount() ?: error("Not signed in")
        GoogleAuthUtil.getToken(context, account.account!!, "oauth2:$DRIVE_FILE_SCOPE")
    }

    suspend fun signOut() = suspendCancellableCoroutine<Unit> { cont ->
        client.signOut().addOnCompleteListener { cont.resume(Unit) }
    }
}
