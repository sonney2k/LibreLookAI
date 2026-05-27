package com.librelookai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.librelookai.ml.EmbeddingService
import com.librelookai.util.Analytics

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Analytics.init(applicationContext)
        EmbeddingService.init(this)
        com.librelookai.gemini.PricingClient.start(applicationContext)
        com.librelookai.gemini.ModelPricingClient.start(applicationContext)
        setContent { AppContent(this) }
    }
}
