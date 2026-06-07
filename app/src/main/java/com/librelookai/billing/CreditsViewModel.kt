package com.librelookai.billing
import android.app.Activity
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "CreditsViewModel"

data class CreditsUiState(
    val isManagedMode: Boolean = false,
    val balance: Int = 0,
    val products: List<ProductDetails> = emptyList(),
    val isLoadingProducts: Boolean = false,
    val isPurchasing: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null,
)

class CreditsViewModel(app: Application) : AndroidViewModel(app) {

    val billing = BillingRepository(app)
    val credits = CreditRepository(app)

    private val _state = MutableStateFlow(CreditsUiState(isManagedMode = credits.isManagedMode()))
    val state: StateFlow<CreditsUiState> = _state.asStateFlow()

    init {
        // BYOK-only builds never touch Play billing or the credit balance.
        if (ManagedBilling.enabled) {
            // Connect billing and observe balance
            billing.connect()

            // Track billing connection state → fetch product details when ready
            viewModelScope.launch {
                billing.isConnected.collectLatest { connected ->
                    if (connected && _state.value.products.isEmpty()) loadProducts()
                }
            }

            // Real-time credit balance
            viewModelScope.launch {
                credits.balanceFlow.collect { balance ->
                    _state.update { it.copy(balance = balance) }
                }
            }

            // Process any unacknowledged purchases from a previous session
            viewModelScope.launch {
                billing.purchaseUpdates.collect { (result, purchases) ->
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        purchases.forEach { processPurchase(it) }
                    }
                }
            }
        }
    }

    private fun loadProducts() {
        viewModelScope.launch {
            _state.update { it.copy(isLoadingProducts = true) }
            val products = billing.queryProductDetails()
            // Sort by ascending credits so cheapest pack is first
            val sorted = products.sortedBy { CreditPacks.creditsFor(it.productId) }
            _state.update { it.copy(products = sorted, isLoadingProducts = false) }
        }
    }

    /** Re-processes any purchases that were interrupted (app killed mid-purchase). */
    fun processPendingPurchases() {
        viewModelScope.launch {
            billing.queryUnprocessedPurchases().forEach { processPurchase(it) }
        }
    }

    /** Launches the Play billing sheet for [productDetails]. */
    fun purchase(activity: Activity, productDetails: ProductDetails) {
        val result = billing.launchBillingFlow(activity, productDetails)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _state.update { it.copy(error = getApplication<Application>().getString(com.librelookai.R.string.error_billing, result.debugMessage ?: "")) }
        }
    }

    private fun processPurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        viewModelScope.launch {
            _state.update { it.copy(isPurchasing = true) }
            val productId = purchase.products.firstOrNull() ?: return@launch
            val added = credits.verifyPurchase(purchase.purchaseToken, productId)
            if (added > 0) {
                Log.d(TAG, "Purchase verified: +$added credits for $productId")
                _state.update { it.copy(isPurchasing = false, successMessage = getApplication<Application>().getString(com.librelookai.R.string.credits_added, added)) }
            } else {
                Log.w(TAG, "Purchase verification returned -1 for $productId")
                _state.update { it.copy(isPurchasing = false, error = getApplication<Application>().getString(com.librelookai.R.string.error_purchase_verify_failed)) }
            }
        }
    }

    fun clearError()   = _state.update { it.copy(error = null) }
    fun clearSuccess() = _state.update { it.copy(successMessage = null) }

    override fun onCleared() {
        super.onCleared()
        billing.disconnect()
    }
}
