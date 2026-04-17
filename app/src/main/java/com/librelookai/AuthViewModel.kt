package com.librelookai

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthViewModel(app: Application) : AndroidViewModel(app) {

    private val auth = GoogleAuthManager(app)

    private val _isSignedIn = MutableStateFlow(auth.isSignedIn())
    val isSignedIn: StateFlow<Boolean> = _isSignedIn.asStateFlow()

    /** Null = no error; any other int = ApiException status code. */
    private val _signInErrorCode = MutableStateFlow<Int?>(null)
    val signInErrorCode: StateFlow<Int?> = _signInErrorCode.asStateFlow()

    /** Non-null when user interaction is required to grant Drive scope; launch via StartIntentSenderForResult. */
    private val _pendingAuthIntent = MutableStateFlow<PendingIntent?>(null)
    val pendingAuthIntent: StateFlow<PendingIntent?> = _pendingAuthIntent.asStateFlow()

    fun startSignIn() {
        viewModelScope.launch {
            try {
                val pendingIntent = auth.beginSignIn()
                if (pendingIntent == null) {
                    // Already authorized
                    _isSignedIn.value = true
                    _signInErrorCode.value = null
                } else {
                    _pendingAuthIntent.value = pendingIntent
                }
            } catch (e: ApiException) {
                _signInErrorCode.value = e.statusCode
            } catch (e: Exception) {
                _signInErrorCode.value = -2
            }
        }
    }

    fun onAuthorizationResult(data: Intent?) {
        _pendingAuthIntent.value = null
        val success = auth.handleAuthorizationResult(data)
        if (success) {
            _isSignedIn.value = true
            _signInErrorCode.value = null
        } else {
            _signInErrorCode.value = -2
        }
    }

    /** Best-effort Firebase sign-in after Drive authorization. Requires Activity context. */
    fun attemptFirebaseSignIn(activity: ComponentActivity) {
        viewModelScope.launch { auth.signInToFirebase(activity) }
    }

    fun signOut() {
        viewModelScope.launch {
            auth.signOut()
            _isSignedIn.value = false
        }
    }

    fun clearError() { _signInErrorCode.value = null }
}
