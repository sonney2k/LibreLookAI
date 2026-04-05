package com.librelookai

import java.time.LocalDate

data class WornItem(
    val date: LocalDate,
    val imagePath: String? = null,
    val label: String = "",
)
