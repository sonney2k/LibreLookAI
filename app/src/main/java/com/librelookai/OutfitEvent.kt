package com.librelookai

import java.util.UUID

data class OutfitEvent(
    val id: String = UUID.randomUUID().toString(),
    val styleId: String,
    /** ISO local date, e.g. "2026-04-05". */
    val date: String,
    val createdAt: Long = System.currentTimeMillis(),
)
