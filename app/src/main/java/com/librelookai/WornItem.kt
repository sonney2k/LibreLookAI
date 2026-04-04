package com.librelookai

import android.net.Uri
import java.time.LocalDate

data class WornItem(
    val date: LocalDate,
    val imageUri: Uri? = null,
    val label: String = "",
)
