package com.librelookai.data.model
import com.google.gson.annotations.SerializedName
import java.util.UUID

data class OutfitEvent(
    val id: String = UUID.randomUUID().toString(),
    /** Id of the saved outfit worn on this date. JSON name kept as "styleId" for backward compat. */
    @SerializedName(value = "outfitId", alternate = ["styleId"])
    val outfitId: String,
    /** ISO local date, e.g. "2026-04-05". */
    val date: String,
    val createdAt: Long = System.currentTimeMillis(),
)
