package com.librelookai

import java.util.UUID

data class Style(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val description: String = "",
    /** Runtime Drive file IDs for the current session. Populated from [itemNames] on load. */
    val itemIds: List<String> = emptyList(),
    /**
     * Stable filenames stored in the JSON. Filenames survive copying a Drive folder to a new
     * account (unlike Drive file IDs which change on copy). On load, these are resolved back
     * to current Drive IDs via the folder file listing.
     */
    val itemNames: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    /** Runtime Drive folder ID this style was loaded from. Not persisted to JSON. */
    @Transient val folderId: String = "",
)
