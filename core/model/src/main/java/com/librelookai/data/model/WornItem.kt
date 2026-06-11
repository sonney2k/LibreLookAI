package com.librelookai.data.model
import java.time.LocalDate

data class WornItem(
    val date: LocalDate,
    val imagePath: String? = null,
    val label: String = "",
)
