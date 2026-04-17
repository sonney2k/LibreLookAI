package com.librelookai

import android.app.Application
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthViewModel(app: Application) : AndroidViewModel(app) {

    private val auth = GoogleAuthManager(app)

    private val _isSignedIn = MutableStateFlow(auth.isSignedIn())
    val isSignedIn: StateFlow<Boolean> = _isSignedIn.asStateFlow()

    /** Null = no error; -1 = cancelled (silent); any other int = ApiException status code. */
    private val _signInErrorCode = MutableStateFlow<Int?>(null)
    val signInErrorCode: StateFlow<Int?> = _signInErrorCode.asStateFlow()

    fun getSignInIntent(): Intent = auth.getSignInIntent()

    fun onSignInResult(result: ActivityResult) {
        var errorCode: Int? = null
        val googleSuccess = try {
            GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(ApiException::class.java)
            true
        } catch (e: ApiException) {
            errorCode = e.statusCode
            auth.isSignedIn()   // fallback: already persisted with correct scope
        }

        if (googleSuccess) {
            _isSignedIn.value = true
            _signInErrorCode.value = null
            // Best-effort Firebase sign-in for managed mode — runs in background
            viewModelScope.launch { auth.signInToFirebase() }
        } else {
            // Suppress cancelled (user dismissed the picker) — not an error worth showing
            _signInErrorCode.value = if (errorCode == GoogleSignInStatusCodes.SIGN_IN_CANCELLED) null else errorCode
        }
    }

    fun signOut() {
        viewModelScope.launch {
            auth.signOut()
            _isSignedIn.value = false
        }
    }

    fun clearError() { _signInErrorCode.value = null }
}
