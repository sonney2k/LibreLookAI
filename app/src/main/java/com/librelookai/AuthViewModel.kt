package com.librelookai

import android.app.Application
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthViewModel(app: Application) : AndroidViewModel(app) {

    private val auth = GoogleAuthManager(app)

    private val _isSignedIn = MutableStateFlow(auth.isSignedIn())
    val isSignedIn: StateFlow<Boolean> = _isSignedIn.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun getSignInIntent(): Intent = auth.getSignInIntent()

    fun onSignInResult(result: ActivityResult) {
        val googleSuccess = try {
            GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(ApiException::class.java)
            true
        } catch (e: ApiException) {
            auth.isSignedIn()   // fallback: already persisted with correct scope
        }

        if (googleSuccess) {
            _isSignedIn.value = true
            _error.value = null
            // Best-effort Firebase sign-in for managed mode — runs in background
            viewModelScope.launch { auth.signInToFirebase() }
        } else {
            _error.value = "Sign-in failed"
        }
    }

    fun signOut() {
        viewModelScope.launch {
            auth.signOut()
            _isSignedIn.value = false
        }
    }

    fun clearError() { _error.value = null }
}
