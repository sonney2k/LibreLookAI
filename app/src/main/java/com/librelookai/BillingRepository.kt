package com.librelookai

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val TAG = "BillingRepository"

class BillingRepository(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Emits (BillingResult, purchases) every time a purchase update arrives. */
    private val _purchaseUpdates =
        MutableSharedFlow<Pair<BillingResult, List<Purchase>>>(extraBufferCapacity = 8)
    val purchaseUpdates: SharedFlow<Pair<BillingResult, List<Purchase>>> =
        _purchaseUpdates.asSharedFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val billingClient = BillingClient.newBuilder(context)
        .setListener { result, purchases ->
            scope.launch {
                _purchaseUpdates.emit(result to (purchases ?: emptyList()))
            }
        }
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        .build()

    fun connect() {
        if (billingClient.isReady) { _isConnected.value = true; return }
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                val ok = result.responseCode == BillingClient.BillingResponseCode.OK
                _isConnected.value = ok
                Log.d(TAG, "Billing setup finished: ${result.responseCode} ${result.debugMessage}")
            }
            override fun onBillingServiceDisconnected() {
                _isConnected.value = false
                Log.d(TAG, "Billing service disconnected")
            }
        })
    }

    fun disconnect() {
        billingClient.endConnection()
        scope.cancel()
    }

    /** Queries Google Play for the details (including price) of all credit packs. */
    suspend fun queryProductDetails(): List<ProductDetails> {
        if (!_isConnected.value) return emptyList()
        val productList = CreditPacks.productIds.map { id ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()
        return suspendCoroutine { cont ->
            billingClient.queryProductDetailsAsync(params) { result, details ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    cont.resume(details)
                } else {
                    Log.w(TAG, "queryProductDetails failed: ${result.debugMessage}")
                    cont.resume(emptyList())
                }
            }
        }
    }

    /** Launches the Play billing UI. Returns a BillingResult immediately; the purchase result
     *  arrives asynchronously through [purchaseUpdates]. */
    fun launchBillingFlow(activity: Activity, productDetails: ProductDetails): BillingResult {
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .build(),
                ),
            )
            .build()
        return billingClient.launchBillingFlow(activity, params)
    }

    /** Returns any INAPP purchases that have not yet been consumed/acknowledged. */
    suspend fun queryUnprocessedPurchases(): List<Purchase> {
        if (!_isConnected.value) return emptyList()
        return suspendCoroutine { cont ->
            billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build(),
            ) { _, purchases -> cont.resume(purchases) }
        }
    }
}
