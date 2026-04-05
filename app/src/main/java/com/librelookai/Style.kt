package com.librelookai

import java.util.UUID

data class Style(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val description: String = "",
    val itemIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)
