package com.librelookai.billing
import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import com.librelookai.gemini.ApiKeyStore
import com.librelookai.data.drive.await
import com.librelookai.feature.billing.BuildConfig

private const val TAG = "CreditRepository"

class CreditRepository(private val app: Application) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** True when Firebase has been initialised (google-services.json present). */
    fun isFirebaseAvailable(): Boolean = try {
        FirebaseApp.getInstance(); true
    } catch (e: IllegalStateException) { false }

    /** True when the app is configured to use the managed proxy (no local API key + proxy URL set). */
    fun isManagedMode(): Boolean =
        ManagedBilling.enabled &&
            ApiKeyStore.get(app).isBlank() &&
            BuildConfig.PROXY_BASE_URL.isNotBlank() &&
            isFirebaseAvailable()

    /**
     * Real-time credit balance for the signed-in user.
     * Emits 0 immediately if Firebase is unavailable or user is not signed in.
     */
    val balanceFlow: Flow<Int> = if (isFirebaseAvailable()) callbackFlow {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) { trySend(0); close(); return@callbackFlow }
        val listener = FirebaseFirestore.getInstance()
            .collection("users").document(user.uid)
            .addSnapshotListener { snap, err ->
                if (err != null) { Log.w(TAG, "Balance listener error", err); return@addSnapshotListener }
                trySend(snap?.getLong("credits")?.toInt() ?: 0)
            }
        awaitClose { listener.remove() }
    } else flowOf(0)

    /**
     * Sends the Google Play purchase token to the proxy server, which validates it
     * and adds credits to the user's Firestore document.
     * Returns the number of credits added, or -1 on failure.
     */
    suspend fun verifyPurchase(purchaseToken: String, productId: String): Int =
        withContext(Dispatchers.IO) {
            val proxyBase = BuildConfig.PROXY_BASE_URL
            if (proxyBase.isBlank() || !isFirebaseAvailable()) return@withContext -1

            val firebaseToken = getFirebaseIdToken() ?: run {
                Log.w(TAG, "verifyPurchase: Firebase user not signed in")
                return@withContext -1
            }
            val body = """{"purchaseToken":"$purchaseToken","productId":"$productId"}"""
            val request = Request.Builder()
                .url("$proxyBase/verifyPurchase")
                .post(body.toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $firebaseToken")
                .build()
            return@withContext try {
                val resp = http.newCall(request).execute()
                Log.d(TAG, "verifyPurchase HTTP ${resp.code}")
                if (resp.isSuccessful) CreditPacks.creditsFor(productId) else -1
            } catch (e: Exception) {
                Log.e(TAG, "verifyPurchase failed", e)
                -1
            }
        }

    suspend fun getFirebaseIdToken(): String? = try {
        FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
    } catch (e: Exception) { null }
}
